package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j// 使用lombok的日志注解
// IUserService接口的实现类
// ServiceImpl是MyBatisPlus提供的一个实现类，实现了IService接口，提供了基本的增删改查功能，User是实体类，UserMapper是Mapper接口
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;// 注入redisTemplate

    @Override// 重写sendCode方法
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号是否合法，利用util包下的正则表达式工具类
        if(RegexUtils.isPhoneInvalid(phone)){
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号码格式错误");
        }
        // 3.如果符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
//         4.保存验证码到session
//        session.setAttribute("code",code);
        // 4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code, LOGIN_CODE_TTL, TimeUnit.MINUTES);// 利用常量设置前缀区分业务，短信验证码有效期2分钟
        // 5.发送验证码到手机(模拟)
        log.debug("向手机{}发送验证码成功，验证码:{}",phone,code);
        // 6.返回ok(自定义的Result对象)
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 登录与注册合二为一
        // 1.校验手机号是否合法(为每一个请求做独立的校验)
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号码格式错误");
        }
        // 3.从redis中获取验证码用于后续校验
//        Object cacheCode = session.getAttribute("code");// 从session中获取发送的验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        // 4.校验验证码是否正确
        String code = loginForm.getCode();// 从表单中获取用户提交的验证码
        if(cacheCode == null || !cacheCode.equals(code)){// 未发送验证码或者验证码不正确
            // 5.验证码错误，返回错误信息
            return Result.fail("验证码错误");// 反向逻辑，避免if嵌套
        }
        // 6.验证码正确，根据手机号查询用户，利用MyBatisPlus实现。本类的父类ServiceImpl由MyBatisPlus提供，具有基本的单表增删改查功能
        User user = query().eq("phone",phone).one();// 根据手机号码查询一个用户
        // 7.判断用户是否存在
        if(user == null){
            // 8.用户不存在，注册用户，保存信息到数据库
            user = createUserWithPhone(phone);// 创建用户并返回，确保保存用户信息时用户存在
        }
//        // 9.无论注册还是登录，都要保存用户信息到session
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));// 将User对象转换为UserDTO对象，再保存到session
        // 9. 保存用户信息到redis
        // 9.1.生成随机token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 9.2将User->UserDTO对象转换为HashMap
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap =
                BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        // 9.3将token和HashMap保存到redis，设置有效期为30分钟
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);// 一次性保存多个键值对，设置前缀区分业务
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);// 设置有效期
        // 9.4返回登录凭证token给客户端
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        // 1 获取当前登陆的用户
        Long userId = UserHolder.getUser().getId();
        // 2 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();// 从1到31
        // 5 写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1 获取当前登陆的用户
        Long userId = UserHolder.getUser().getId();
        // 2 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();// 从1到31
        // 5 获取本月截至今天的所有签到记录(返回十进制数字) BITFIELD key GET u[dayOfMonth] 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(result == null || result.isEmpty()){// 无签到记录
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0){
            return Result.ok(0);
        }
        // 6 循环遍历
        int count = 0;
        while(true) {
            // 6.1 令该数字和1做与运算，得到数字的最后一个bit位，判断该bit位是0还是1
            if ((num & 1)==0) {
                // 6.2 如果是0，说明未签到
                break;
            }else {
                // 6.3 如果是1，说明已签到，计数器+1
                count++;
            }
            // 6.4 将数字右移一位，抛弃最后一个bit位，继续循环判断后续的bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        // 主要信息为手机号码，其它信息(密码、昵称、头像...)为空或随机
        User user = new User();
        user.setPhone(phone);
//        user.setNickname("user_"+ RandomUtil.randomNumbers(10));
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));// 利用SystemConstants中的常量USER_NICK_NAME_PREFIX作为统一的昵称前缀
        save(user);// 利用MyBatisPlus提供的保存方法，保存用户信息到数据库
        return user;
    }
}