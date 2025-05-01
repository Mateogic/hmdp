package com.hmdp.utils;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

// 登录拦截器
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 执行具体的拦截操作(依据:TreadLocal中是否有用户信息)
        if(UserHolder.getUser() == null){
            // 若无用户信息，返回401状态码，拦截
            response.setStatus(401);
            return false;
        }
        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 释放资源:移除TreadLocal中的用户信息，避免内存泄漏
        UserHolder.removeUser();
    }
}
