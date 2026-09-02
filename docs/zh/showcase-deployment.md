# 静态 Showcase 发布

P0 的公开入口是静态文件，不需要 NewsClaw 后端、数据库、模型、搜索供应商或 JWT。

后端镜像发布时固定基础镜像 digest，并建议关闭 BuildKit 的时间变动 provenance：

```bash
docker build --provenance=false -t newsclaw-server:<release> -f newsclaw-server/Dockerfile .
# Then set NEWSCLAW_SERVER_IMAGE=newsclaw-server:<release> and run Compose with --no-build.
```

```bash
cd newsclaw-ui
pnpm install --frozen-lockfile
pnpm exec vite build --outDir /tmp/newsclaw-ui-dist --emptyOutDir
mkdir -p /srv/newsclaw
cp -a /tmp/newsclaw-ui-dist/. /srv/newsclaw/
```

把 `deploy/nginx/newsclaw-showcase.conf.example` 中的域名和证书路径替换后启用。该配置只公开 443 上的静态目录；Compose 的 Spring Boot、1455、数据库和搜索服务保持回环/内部网络，不要把它们反代到公网。

页面中的 30 条 policy regression、90 条 focused backend regression 和 9 条安全契约均是离线/合成证据，不是开放网络准确率或真实平台发布 ACK。
