FROM python:3.12-slim-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends libreoffice-writer libreoffice-calc poppler-utils tesseract-ocr tesseract-ocr-eng tesseract-ocr-ara fonts-liberation fonts-dejavu-core fonts-noto-core && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY engine.py worker.py server.py ./
RUN useradd -m -u 10001 converter
USER converter
ENV FILEFLIP_BIND=0.0.0.0
EXPOSE 8080
CMD ["python", "server.py"]
