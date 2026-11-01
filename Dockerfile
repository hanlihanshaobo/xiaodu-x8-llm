# 服务端镜像：Python + 语音链路
# 模型（ASR / LLM / TTS）都在外部服务里，镜像本身很小
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    TZ=Asia/Shanghai

WORKDIR /app

# 先装依赖，利用层缓存
COPY server/requirements.txt ./server/requirements.txt
RUN pip install --no-cache-dir -r server/requirements.txt

# 再拷贝代码
COPY server/ ./server/

# 运行期目录（挂卷后会覆盖）
RUN mkdir -p server/cache server/logs

EXPOSE 8765

# 配置默认从 server/config.yaml 读；建议用 volume 挂载
CMD ["python", "server/run.py"]
