FROM python:3.12-slim

WORKDIR /app

RUN groupadd -r bourso && useradd -r -g bourso bourso

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .

RUN chown -R bourso:bourso /app
USER bourso

EXPOSE 8001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
