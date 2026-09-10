FROM python:3.10-slim

WORKDIR /app

# 先装依赖（CPU 版 PyTorch，体积小）
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    --extra-index-url https://download.pytorch.org/whl/cpu

COPY main.py .
COPY download_model_modelscope.py .

# 模型通过 volume 挂载到 /app/models/bge-m3，
# 未挂载时首次启动自动从 HuggingFace 下载（国内可用 HF_ENDPOINT 镜像加速）
ENV EMBEDDING_DEVICE=cpu \
    HF_ENDPOINT=https://hf-mirror.com

EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
