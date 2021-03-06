package com.example.oauth2.api.controller;

import com.example.oauth2.api.entity.AuthReqModel;
import com.example.oauth2.api.entity.UserRefreshToken;
import com.example.oauth2.api.repository.UserRefreshTokenRepository;
import com.example.oauth2.common.ApiResponse;
import com.example.oauth2.config.AppProperties;
import com.example.oauth2.oauth.entity.RoleType;
import com.example.oauth2.oauth.entity.UserPrincipal;
import com.example.oauth2.oauth.token.AuthToken;
import com.example.oauth2.oauth.token.AuthTokenProvider;
import com.example.oauth2.utils.HeaderUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AppProperties appProperties;
    private final AuthTokenProvider tokenProvider;
    private final AuthenticationManager authenticationManager;
    private final UserRefreshTokenRepository userRefreshTokenRepository;

    private final static long THREE_DAYS_MSEC = 259200000;
    private final static String REFRESH_TOKEN = "refresh_token";

    @PostMapping("/login")
    public ApiResponse login(@RequestBody AuthReqModel authReqModel) {
        log.info("/login");
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        authReqModel.getEmail(),
                        authReqModel.getPassword()
                )
        );

        String email = authReqModel.getEmail();
        SecurityContextHolder.getContext().setAuthentication(authentication);

        Date now = new Date();
        AuthToken accessToken = tokenProvider.createAuthToken(
                email,
                ((UserPrincipal) authentication.getPrincipal()).getRoleType().getCode(),
                new Date(now.getTime() + appProperties.getAuth().getTokenExpiry())
        );

        long refreshTokenExpiry = appProperties.getAuth().getRefreshTokenExpiry();
        AuthToken refreshToken = tokenProvider.createAuthToken(
                appProperties.getAuth().getTokenSecret(),
                new Date(now.getTime() + refreshTokenExpiry)
        );

        // userId refresh token ?????? DB ??????
        UserRefreshToken userRefreshToken = userRefreshTokenRepository.findByUserId(email);
        if (userRefreshToken == null) {
            // ?????? ?????? ?????? ??????
            userRefreshToken = new UserRefreshToken(email, refreshToken.getToken());
            userRefreshTokenRepository.saveAndFlush(userRefreshToken);
        } else {
            // DB??? refresh ?????? ????????????
            userRefreshToken.setRefreshToken(refreshToken.getToken());
        }

//        int cookieMaxAge = (int) refreshTokenExpiry / 60;
//        CookieUtil.deleteCookie(request, response, REFRESH_TOKEN);
//        CookieUtil.addCookie(response, REFRESH_TOKEN, refreshToken.getToken(), cookieMaxAge);

        return ApiResponse.success("token", accessToken.getToken());
    }

    @GetMapping("/refresh")
    public ApiResponse refreshToken (HttpServletRequest request, HttpServletResponse response) {
        log.info("/refresh...");
        // access token ??????

        log.info("getAccessToken");
        String accessToken = HeaderUtil.getAccessToken(request);
        AuthToken authToken = tokenProvider.convertAuthToken(accessToken);

        // expired access token ?????? ??????
        Claims claims = authToken.getExpiredTokenClaims();
        if (claims == null) {
            return ApiResponse.notExpiredTokenYet();
        }

        String userId = claims.getSubject();
        RoleType roleType = RoleType.of(claims.get("role", String.class));

        // refresh token
        log.info("getRefreshToken");
        String refreshToken = HeaderUtil.getRefreshToken(request);
        AuthToken authRefreshToken = tokenProvider.convertAuthToken(refreshToken);
        if (!authRefreshToken.validate()) {
            return ApiResponse.invalidRefreshToken();
        }
        log.info(userId, refreshToken);
        // userId refresh token ?????? DB ??????
        UserRefreshToken userRefreshToken = userRefreshTokenRepository.findByUserIdAndRefreshToken(userId, refreshToken);
        log.info("userRefreshToken : " + userRefreshToken);
        if (userRefreshToken == null) {
            return ApiResponse.invalidRefreshToken();
        }

        Date now = new Date();
        AuthToken newAccessToken = tokenProvider.createAuthToken(
                userId,
                roleType.getCode(),
                new Date(now.getTime() + appProperties.getAuth().getTokenExpiry())
        );
        long validTime = authRefreshToken.getTokenClaims().getExpiration().getTime() - now.getTime();

        // refresh ?????? ????????? 3??? ????????? ?????? ??????, refresh ?????? ??????
        if (validTime <= THREE_DAYS_MSEC) {
            // refresh ?????? ??????
            long refreshTokenExpiry = appProperties.getAuth().getRefreshTokenExpiry();

            authRefreshToken = tokenProvider.createAuthToken(
                    appProperties.getAuth().getTokenSecret(),
                    new Date(now.getTime() + refreshTokenExpiry)
            );

            // DB??? refresh ?????? ???????????? ?????????
            userRefreshToken.setRefreshToken(authRefreshToken.getToken());

        }

        Map<String, String> token = new HashMap<>();
        token.put("access", newAccessToken.getToken());
        token.put("refresh", authRefreshToken.getToken());
        return ApiResponse.success(token);
    }
}
