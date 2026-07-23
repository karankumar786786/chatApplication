FROM oven/bun:1-alpine AS base
WORKDIR /app

# Install dependencies first (cached layer)
COPY package.json bun.lock* ./
RUN bun install --frozen-lockfile --production

# Copy source
COPY . .

EXPOSE 3000 4000

CMD ["bun", "run", "index.ts"]
