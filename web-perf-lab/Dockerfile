FROM node:20-alpine

WORKDIR /app

# 零依赖：没有 npm install，构建只有 COPY。
# 这是刻意的 —— 演练场是拿来练 DevTools 的，服务端越透明越好，
# 而且没有依赖就没有供应链风险，构建也快。
COPY server.js ./
COPY public ./public

ENV PORT=8080
EXPOSE 8080

# 用非 root 跑。node 镜像自带 uid 1000 的 node 用户。
USER node

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD node -e "require('http').get('http://127.0.0.1:8080/api/health',r=>process.exit(r.statusCode===200?0:1)).on('error',()=>process.exit(1))"

CMD ["node", "server.js"]
