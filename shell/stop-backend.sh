#!/bin/bash

# --- 配置项 ---
# 第一个实例的端口
PORT1=8081
# 第二个实例的端口
PORT2=8082
# Docker 相关配置
DOCKER_CMD="docker"
DOCKER_NGINX_NAME="hmdp-nginx-frontend"
DOCKER_REDIS_NAME="hmdp-redis"
DOCKER_STOP_TIMEOUT=5 # 等待容器停止的秒数
# 应用 JAR 名称的一部分，用于确认进程 (可选，用于更精确地查找)
# APP_NAME_PART="hm-dianping"

# --- 函数定义 ---

# 检查 Docker 环境
check_docker() {
  if ! command -v ${DOCKER_CMD} &> /dev/null; then
    echo "错误: Docker 命令 (${DOCKER_CMD}) 未找到或无法执行。"
    echo "请确保 Docker 已安装并正在运行，且当前用户有权限执行 Docker 命令。"
    # 不直接退出，因为可能只需要停止 Java 进程
    echo "警告: Docker 环境异常，将跳过 Docker 容器停止操作。"
    return 1
  fi
  echo "Docker 环境检查通过: $(${DOCKER_CMD} --version)"
  return 0
}

# 停止指定的 Docker 容器 (如果正在运行)
stop_container() {
  local container_name=$1
  echo "-----------------------------------------------------"
  echo "检查并尝试停止 Docker 容器: ${container_name}"

  # 检查容器是否正在运行
  if ! ${DOCKER_CMD} ps -q --filter "name=^/${container_name}$" --filter "status=running" | grep -q .; then
    echo "信息: 容器 '${container_name}' 未在运行。"
    echo "-----------------------------------------------------"
    return 0
  fi

  # 容器正在运行，尝试停止
  echo "容器 '${container_name}' 正在运行，尝试停止..."
  ${DOCKER_CMD} stop -t ${DOCKER_STOP_TIMEOUT} ${container_name}
  local stop_status=$?

  # 检查停止操作的状态
  if ${DOCKER_CMD} ps -q --filter "name=^/${container_name}$" --filter "status=running" | grep -q .; then
     echo "警告: 停止容器 '${container_name}' 超时或失败。它可能仍在运行。"
     echo "请手动检查: '${DOCKER_CMD} ps -a -f name=${container_name}'"
     echo "-----------------------------------------------------"
     # 即使停止失败，也返回0，避免阻塞脚本，但打印警告
     return 0 # 或者可以返回 1 表示有问题
  else
     if [ ${stop_status} -eq 0 ]; then
       echo "成功: 容器 '${container_name}' 已停止。"
     else
       # stop 命令本身可能失败，即使容器最后停了
       echo "信息: 容器 '${container_name}' 已停止 (stop 命令退出状态非0)。"
     fi
     echo "-----------------------------------------------------"
     return 0
  fi
}

# 停止指定端口的进程
stop_process_on_port() {
  local port=$1
  echo "-----------------------------------------------------"
  echo "尝试停止监听端口 ${port} 的服务..."

  # 查找 PID
  # 优先使用 lsof，因为它更可靠且输出简洁
  local pid=$(lsof -ti :${port} 2>/dev/null)

  # 可选：如果需要更精确匹配，可以结合 ps 和 grep (如果上面 lsof 找到多个或不确定时)
  # if [ -n "$pid" ] && [ -n "$APP_NAME_PART" ]; then
  #   if ! ps -p ${pid} -o command= | grep -q "${APP_NAME_PART}"; then
  #      echo "警告: 端口 ${port} 的进程 PID ${pid} 似乎不是目标应用 (${APP_NAME_PART})，将跳过停止。"
  #      pid="" # 清除 PID，避免误杀
  #   fi
  # fi

  if [ -z "$pid" ]; then
    echo "信息: 未找到监听端口 ${port} 的进程。"
    echo "-----------------------------------------------------"
    return 0 # 没找到进程也算成功完成此端口的处理
  fi

  echo "发现进程 PID: ${pid} 正在监听端口 ${port}。"

  # 尝试正常停止 (SIGTERM)
  echo "发送 SIGTERM 信号给 PID: ${pid} ..."
  kill ${pid}
  sleep 3 # 等待进程响应

  # 检查进程是否仍在运行
  if kill -0 ${pid} 2>/dev/null; then
    echo "警告: PID ${pid} 未能正常退出，尝试强制停止 (SIGKILL)..."
    kill -9 ${pid}
    sleep 1 # 短暂等待 kill -9 生效

    # 再次检查
    if kill -0 ${pid} 2>/dev/null; then
       echo "错误: 强制停止 PID ${pid} (端口 ${port}) 失败！请手动检查。"
       echo "-----------------------------------------------------"
       return 1 # 标记失败
    else
       echo "成功: 进程 PID ${pid} (端口 ${port}) 已被强制停止。"
       echo "-----------------------------------------------------"
       return 0 # 标记成功
    fi
  else
    echo "成功: 进程 PID ${pid} (端口 ${port}) 已正常停止。"
    echo "-----------------------------------------------------"
    return 0 # 标记成功
  fi
}

# --- 主逻辑 ---
echo "==== 黑马点评后端服务停止脚本 ===="
date

# 检查 lsof 命令是否存在
if ! command -v lsof &> /dev/null; then
    echo "错误: 本脚本需要 'lsof' 命令来查找进程。请先安装 lsof。"
    # 可以尝试添加 netstat 等备用方案，但 lsof 通常更优
    # echo "尝试使用 'netstat' 作为备选..."
    exit 1
fi

# 检查 Docker 环境 (检查但不强制退出)
check_docker
DOCKER_AVAILABLE=$?

# 停止第一个实例
stop_process_on_port ${PORT1}
STOP1_STATUS=$?

# 停止第二个实例
stop_process_on_port ${PORT2}
STOP2_STATUS=$?

# 如果 Docker 可用，则停止 Docker 容器
STOP_NGINX_STATUS=0
STOP_REDIS_STATUS=0
if [ ${DOCKER_AVAILABLE} -eq 0 ]; then
    stop_container ${DOCKER_NGINX_NAME}
    STOP_NGINX_STATUS=$?
    stop_container ${DOCKER_REDIS_NAME}
    STOP_REDIS_STATUS=$?
else
    echo "==== 跳过 Docker 容器停止操作 ===="
fi

echo "==== 脚本执行完毕 ===="
FINAL_STATUS=0

if [ ${STOP1_STATUS} -ne 0 ] || [ ${STOP2_STATUS} -ne 0 ]; then
    echo "警告: 停止一个或多个 Java 服务实例时遇到问题。请检查上面的输出。"
    FINAL_STATUS=1
fi

if [ ${DOCKER_AVAILABLE} -eq 0 ]; then
    if [ ${STOP_NGINX_STATUS} -ne 0 ] || [ ${STOP_REDIS_STATUS} -ne 0 ]; then
        echo "警告: 停止一个或多个 Docker 容器时遇到问题 (或超时)。请检查上面的输出。"
        # 如果你希望 Docker 停止失败也导致脚本失败退出，取消下一行的注释
        # FINAL_STATUS=1
    else
        echo "Docker 容器 (${DOCKER_NGINX_NAME}, ${DOCKER_REDIS_NAME}) 停止操作已完成。"
    fi
else
     echo "由于 Docker 环境问题，已跳过 Docker 容器停止操作。"
fi

if [ ${FINAL_STATUS} -eq 0 ]; then
    echo "所有目标服务停止操作已成功完成 (或按预期跳过)。"
    echo "建议使用 'ps -ef | grep java' 和 'docker ps -a' 再次确认进程/容器已退出。"
    exit 0
else
    echo "停止操作遇到问题，请检查日志。"
    exit 1
fi
