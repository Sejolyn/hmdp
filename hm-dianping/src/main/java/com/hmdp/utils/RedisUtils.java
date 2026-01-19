package com.hmdp.utils;

public class RedisUtils {
    public static String getTokenCacheKey(String token) {
        return RedisConstants.LOGIN_USER_KEY + token;
    }

    public static String getLoginCodeKey(String phone) {
        return RedisConstants.LOGIN_CODE_KEY + phone;
    }

    public static String getCodeCacheKey(String phone) {
        return RedisConstants.LOGIN_CODE_KEY + phone;
    }

    public static String getShopCacheKey(Long id) {
        return RedisConstants.CACHE_SHOP_KEY + id;
    }

}
