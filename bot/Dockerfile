FROM node:20-slim

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm install --omit=dev --no-audit --no-fund
COPY src ./src
COPY data ./data

CMD ["node", "src/index.js"]
