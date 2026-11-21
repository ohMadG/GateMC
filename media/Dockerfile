FROM node:20-slim

WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install --omit=dev --no-audit --no-fund
COPY src ./src
COPY views ./views
COPY public ./public
COPY sql ./sql

EXPOSE 8104
CMD ["node", "src/server.js"]
