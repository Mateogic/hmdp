package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// token刷新拦截器
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;
    // 使用构造器注入redisTemplate
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 实现校验登录状态功能
        // 1.获取请求头中的token
        String token = request.getHeader("Authorization");
        if(StrUtil.isBlank(token)){
            return true;// 不执行具体的拦截操作，交由后续的LoginInterceptor拦截器处理
        }
        // 2.根据token获取redis中的用户信息
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap =
                stringRedisTemplate.opsForHash().entries(tokenKey);
        // 3.判断用户是否存在
        if(userMap.isEmpty()){
            return true;
        }
        // 5.将查询到的HashMap类型的用户信息转换为UserDTO类型
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 6.利用utils包下的UserHolder工具类保存用户信息到TreadLocal
        UserHolder.saveUser(userDTO);
        // 7.更新token的有效期
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 8.放行
        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 释放资源:移除TreadLocal中的用户信息，避免内存泄漏
        UserHolder.removeUser();
    }
}