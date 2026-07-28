package com.szsemicon.hr.shared.web;

import org.springframework.http.HttpStatus;

public final class ChangeReasonHeader {

    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 500;

    private ChangeReasonHeader() {
    }

    public static String requireMatches(String encodedHeader, String bodyReason) {
        String decodedHeader = decodeAndValidate(encodedHeader);
        if (!decodedHeader.equals(bodyReason)) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "CHANGE_REASON_MISMATCH",
                    "变更原因请求头与请求体不一致");
        }
        return decodedHeader;
    }

    public static String decodeAndValidate(String encodedHeader) {
        final String decoded;
        try {
            decoded = Utf8HeaderValue.decode(encodedHeader);
        } catch (IllegalArgumentException exception) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "CHANGE_REASON_INVALID",
                    "变更原因请求头编码无效");
        }
        if (decoded == null
                || decoded.length() < MIN_LENGTH
                || decoded.length() > MAX_LENGTH
                || decoded.isBlank()) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "CHANGE_REASON_INVALID",
                    "变更原因长度必须为 2 至 500 个字符");
        }
        return decoded;
    }
}
