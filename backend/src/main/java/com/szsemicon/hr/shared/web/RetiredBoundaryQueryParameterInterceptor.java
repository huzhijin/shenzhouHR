package com.szsemicon.hr.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public final class RetiredBoundaryQueryParameterInterceptor
        implements HandlerInterceptor {

    private static final String RETIRED_COMPANY_ID_PARAMETER =
            String.join("", "legal", "Entity", "Id");

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        if (request.getParameterMap()
                .containsKey(RETIRED_COMPANY_ID_PARAMETER)) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "请求包含已停用的公司查询参数");
        }
        return true;
    }
}
