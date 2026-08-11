package com.clouddrive.controller;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 分页参数解析（对齐 Go api.ParsePageParams）：page 从 1 起，page_size 限制在 [1,100]。
 */
public final class Pagination {

    private Pagination() {
    }

    public static int[] parse(HttpServletRequest request, int defaultSize) {
        int page = parseInt(request.getParameter("page"), 1);
        int pageSize = parseInt(request.getParameter("page_size"), defaultSize);
        if (page < 1) {
            page = 1;
        }
        if (pageSize < 1 || pageSize > 100) {
            pageSize = defaultSize;
        }
        return new int[]{page, pageSize};
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
