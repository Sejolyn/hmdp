package com.hmdp.service.impl;

import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
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
    private CacheClient cacheClient;

    @Override
    public List<ShopType> queryTypeList() {
        return cacheClient.queryListWithPassThrough(
                RedisConstants.SHOP_TYPE_KEY,
                "", // 无具体ID
                ShopType.class,
                Duration.ofHours(24), // 假设缓存24小时
                id -> query().orderByAsc("sort").list()
        );
    }
}
