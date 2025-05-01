#!/bin/bash
# --- 配置项 ---
# JAR 文件名 (修改为你实际的 JAR 文件名)
JAR_NAME="hm-dianping-0.0.1-SNAPSHOT.jar"
# JAR 文件完整路径
JAR_PATH="/root/wp/hmdp/backend/${JAR_NAME}"
# 第一个实例的端口
PORT1=8081
# 第二个实例的端口
PORT2=8082
# 日志文件目录
LOG_DIR="/root/wp/hmdp/logs"
LOG_FILE1="${LOG_DIR}/hmdp-${PORT1}.log"
LOG_FILE2="${LOG_DIR}/hmdp-${PORT2}.log"
# Java 运行环境路径 (如果 java 不在默认 PATH，需要指定)
# JAVA_HOME="/path/to/your/java"
# JAVA_CMD="${JAVA_HOME}/bin/java"
JAVA_CMD="java" # 假设 java 在 PATH 中
# Docker 相关配置
DOCKER_CMD="docker"
DOCKER_NGINX_NAME="hmdp-nginx-frontend"
DOCKER_REDIS_NAME="hmdp-redis"
DOCKER_START_TIMEOUT=5 # 等待容器启动的秒数
# --- 函数定义 ---
# 检查 Java 环境
check_java() {
  if ! command -v ${JAVA_CMD} &> /dev/null; then
    echo "错误: Java 命令 (${JAVA_CMD}) 未找到或无法执行。"
    echo "请检查 Java 是否已安装并配置到系统 PATH，或者在脚本中设置 JAVA_HOME。"
    exit 1
  fi
  echo "Java 环境检查通过: $(${JAVA_CMD} -version 2>&1 | grep version)"
}
# 检查 Docker 环境
check_docker() {
  if ! command -v ${DOCKER_CMD} &> /dev/null; then
    echo "错误: Docker 命令 (${DOCKER_CMD}) 未找到或无法执行。"
    echo "请确保 Docker 已安装并正在运行，且当前用户有权限执行 Docker 命令。"
    exit 1
  fi
  echo "Docker 环境检查通过: $(${DOCKER_CMD} --version)"
}
# 创建必要的目录
prepare_dirs() {
  mkdir -p ${LOG_DIR}
  echo "日志目录 '${LOG_DIR}' 已准备就绪。"
}

# 启动指定的 Docker 容器 (如果未运行)
start_container() {
  local container_name=$1
  echo "-----------------------------------------------------"
  echo "检查 Docker 容器: ${container_name}"

  # 检查容器是否正在运行 (^/container_name$ 用于精确匹配)
  if ${DOCKER_CMD} ps -q --filter "name=^/${container_name}$" --filter "status=running" | grep -q .; then
    echo "信息: 容器 '${container_name}' 已经在运行。"
    echo "-----------------------------------------------------"
    return 0
  fi

  # 检查容器是否存在 (即使已停止)
  if ! ${DOCKER_CMD} ps -aq --filter "name=^/${container_name}$" | grep -q .; then
     echo "错误: 找不到名为 '${container_name}' 的 Docker 容器。"
     echo "请确保已使用 'docker run' 或 'docker-compose up' 创建了此容器。"
     echo "-----------------------------------------------------"
     return 1
  fi

  # 容器存在但未运行，尝试启动
  echo "容器 '${container_name}' 未运行，正在尝试启动..."
  ${DOCKER_CMD} start ${container_name}
  if [ $? -ne 0 ]; then
      echo "错误: 启动容器 '${container_name}' 失败。请检查 Docker 日志。"
      echo "-----------------------------------------------------"
      return 1
  fi

  echo "等待 ${DOCKER_START_TIMEOUT} 秒让容器 '${container_name}' 初始化..."
  sleep ${DOCKER_START_TIMEOUT}

  # 再次检查容器是否已成功启动
  if ${DOCKER_CMD} ps -q --filter "name=^/${container_name}$" --filter "status=running" | grep -q .; then
    echo "成功: 容器 '${container_name}' 已启动。"
    echo "-----------------------------------------------------"
    return 0
  else
    echo "错误: 启动容器 '${container_name}' 后未能检测到其正在运行。"
    echo "请手动检查容器状态: '${DOCKER_CMD} ps -a -f name=${container_name}' 和日志: '${DOCKER_CMD} logs ${container_name}'"
    echo "-----------------------------------------------------"
    return 1
  fi
}

# 停止指定端口的进程
stop_process_on_port() {
  local port=$1
  # 尝试使用 lsof 查找 PID
  local pid=$(lsof -ti :${port} 2>/dev/null)
  # 如果 lsof 失败或未安装，尝试使用 netstat (语法可能因系统而异)
  # if [ -z "$pid" ]; then
  #   pid=$(netstat -tulnp | grep ":${port} " | awk '{print $7}' | cut -d'/' -f1)
  # fi

  if [ -n "$pid" ]; then
    echo "发现端口 ${port} 被进程 PID: ${pid} 占用，正在尝试停止..."
    kill -9 ${pid}
    sleep 2 # 等待进程退出
    # 再次检查
    if lsof -ti :${port} &> /dev/null; then
        echo "警告: 无法自动停止占用端口 ${port} 的旧进程 (PID: ${pid})。请手动检查并停止。"
    else
        echo "占用端口 ${port} 的旧进程 (PID: ${pid}) 已停止。"
    fi
  else
    echo "端口 ${port} 未被占用。"
  fi
}

# 启动单个服务实例
start_instance() {
  local port=$1
  local log_file=$2
  echo "-----------------------------------------------------"
  echo "准备启动实例:端口=${port}, 日志=${log_file}"
  stop_process_on_port ${port}

  echo "使用命令启动: nohup ${JAVA_CMD} -jar ${JAR_PATH} --server.port=${port} > ${log_file} 2>&1 &"
  nohup ${JAVA_CMD} -jar ${JAR_PATH} --server.port=${port} > ${log_file} 2>&1 &
  # 获取后台任务的 PID
  local instance_pid=$!
  echo "实例已在后台启动，进程 PID: ${instance_pid}"

  echo "等待几秒钟让服务初始化..."
  sleep 30 # 等待时间可能需要根据服务启动速度调整

  # 检查服务是否真正在监听端口
  if lsof -i :${port} > /dev/null; then
     echo "成功: 实例似乎已在端口 ${port} 成功启动。请检查日志确认: ${log_file}"
     echo "-----------------------------------------------------"
     return 0 # 成功
  else
     echo "错误: 启动实例 (端口: ${port}) 失败。未能检测到监听端口。"
     echo "请检查日志文件获取详细错误信息: ${log_file}"
     echo "以及检查 Java 进程状态: ps -ef | grep ${JAR_NAME}"
     echo "-----------------------------------------------------"
     return 1 # 失败
  fi
}

# --- 主逻辑 ---
echo "==== 黑马点评后端服务启动脚本 ===="
date

check_java
check_docker # 新增 Docker 环境检查
prepare_dirs

# 启动依赖的 Docker 容器
start_container ${DOCKER_NGINX_NAME}
if [ $? -ne 0 ]; then exit 1; fi

start_container ${DOCKER_REDIS_NAME}
if [ $? -ne 0 ]; then exit 1; fi

# 检查 JAR 文件是否存在
if [ ! -f "${JAR_PATH}" ]; then
    echo "错误: JAR 文件未找到: ${JAR_PATH}"
    exit 1
fi
echo "使用 JAR 文件: ${JAR_PATH}"

# 启动第一个实例
start_instance ${PORT1} ${LOG_FILE1}
INSTANCE1_STATUS=$? # 获取第一个实例的启动状态

# 启动第二个实例
start_instance ${PORT2} ${LOG_FILE2}
INSTANCE2_STATUS=$? # 获取第二个实例的启动状态

echo "==== 脚本执行完毕 ===="
if [ ${INSTANCE1_STATUS} -eq 0 ] && [ ${INSTANCE2_STATUS} -eq 0 ]; then
echo "两个后端服务实例似乎都已成功启动。"
    echo "实例1 (端口 ${PORT1}) 日志: ${LOG_FILE1}"
    echo "实例2 (端口 ${PORT2}) 日志: ${LOG_FILE2}"
    echo "使用 'ps -ef | grep java' 查看 Java 进程。"
    echo "使用 'tail -f ${LOG_DIR}/hmdp-*.log' 查看实时日志。"
else
    echo "警告: 一个或多个后端服务实例启动失败。请检查上面的输出和日志文件。"
    exit 1
fi

exit 0
