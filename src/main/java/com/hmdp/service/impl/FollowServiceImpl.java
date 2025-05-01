package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1 获取登录的用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;// 登录用户关注了哪些用户
        // 2 判断要执行关注还是取关操作
        if(isFollow) {
            // 3 关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            // 3.1 向MySQL添加数据
            boolean isSuccess = save(follow);
            if (isSuccess){
                // 3.2 将关注的用户id存入Redis
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else {
            // 4 取关，删除数据
            // 4.1 从MySQL删除数据
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess){
                // 4.2 将取关的用户id从Redis中删除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 1 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 2 查询是否已经关注
        Integer count = query()
                .eq("user_id", userId)
                .eq("follow_user_id", followUserId)
                .count();
        // 3 判断并返回
        return Result.ok(count > 0);
    }

    @Override
    public Result commonFollows(Long id) {
        // 1 获取登录用户id
        Long userId = UserHolder.getUser().getId();
        // 2 拼接登录用户id和指定用户id
        String key1 = "follows:" + userId;
        String key2 = "follows:" + id;
        // 3 求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        // 4 空集判断
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 5 解析交集中的id到新的集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 6 查询id交集对应的用户集合
        List<UserDTO> users = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(users);
    }
}
