# 服务端镜像：Python + 语音链路
# 模型（ASR / LLM / TTS）都在外部服务里，镜像本身很小
#
# 多架构：linux/amd64、linux/arm64、linux/arm/v7（32 位 ARM）
FROM python:3.11-slim-bookworm

ARG TARGETARCH
ARG TARGETVARIANT

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    TZ=Asia/Shanghai

WORKDIR /app

# 先装依赖，利用层缓存
COPY server/requirements.txt ./server/requirements.txt

# 32 位 ARM 没有 numpy 官方轮子，改用 piwheels 的 cp311 轮子并锁版本，
# 避免 pip 退回去源码编译 numpy（在 QEMU 里会非常慢甚至失败）。
RUN if [ "$TARGETARCH" = "arm" ] && [ "$TARGETVARIANT" = "v7" ]; then \
        pip install --no-cache-dir --only-binary=:all: \
            --extra-index-url https://www.piwheels.org/simple \
            "numpy>=1.22,<1.23" \
            -r server/requirements.txt; \
    else \
        pip install --no-cache-dir -r server/requirements.txt; \
    fi

# 再拷贝代码
COPY server/ ./server/

# 运行期目录（挂卷后会覆盖）
RUN mkdir -p server/cache server/logs

EXPOSE 8765

# 配置默认从 server/config.yaml 读；建议用 volume 挂载
CMD ["python", "server/run.py"]
