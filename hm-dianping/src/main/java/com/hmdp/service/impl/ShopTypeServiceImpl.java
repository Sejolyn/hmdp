package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryTypeList() {
        // 1. 从 Redis 缓存中查询
        String shopTypeJson = stringRedisTemplate.opsForValue().get(RedisConstants.SHOP_TYPE_KEY);
        if (StrUtil.isNotBlank(shopTypeJson)) {
            // 缓存命中，直接返回
            return JSONUtil.toBean(shopTypeJson, new TypeReference<List<ShopType>>() {}, false);
        }

        // 2. 缓存中不存在，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();

        // 3. 数据库中存在，放入缓存
        stringRedisTemplate.opsForValue().set(RedisConstants.SHOP_TYPE_KEY, JSONUtil.toJsonStr(typeList));

        return typeList;
    }
}
