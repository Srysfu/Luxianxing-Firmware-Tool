# 路先行固件工具 (Luxianxing Firmware Tool)

路先行车载互联盒子的固件刷入与检测工具。支持从阿里云 OSS 抓取官方固件、本地选择固件、8 项可刷入性检测、一键刷入。

## 功能

- **固件抓取**：从 `cpbox.oss-cn-shenzhen.aliyuncs.com` 抓取正式版与测试版固件
- **本地选择**：从文件管理器选择本地 `update.img`
- **固件检测**：8 项可刷入性校验（SWU 头、MD5、CPIO 格式、文件顺序、权限、magic、项目名、文件类型）
- **一键刷入**：连接盒子 → 上传固件 → 轮询状态 → 自动重启
- **查询盒子**：读取设备 IP 与升级状态
- **下载保存**：固件保存到 Download 目录

## 固件格式

```
[0..45)   : 32 字节小写 md5 hex + "  update.swu\n"
[45..end) : cpio crc 归档 (sw-description / customer / kernel)
```

## 刷入流程

1. 手机连接盒子热点（默认 `192.168.1.101`）
2. 上传固件到 `/cgi-bin/index.cgi?id=upload`
3. 轮询 `/cgi-bin/index.cgi?id=host` 的 `update` 字段
4. `update=3`（成功）后触发 `/cgi-bin/index.cgi?id=set&rst=1` 重启

## 升级状态

| update | 含义 |
|--------|------|
| 0 | 空闲 |
| 1 | 开始 |
| 2 | 准备中 |
| 3 | 成功 |
| 4 | 失败 |
| 5 | 校验失败 |

## 技术栈

- Kotlin + Android View XML
- OkHttp 4.12.0
- Gson 2.10.1
- 中英双语 + 日夜模式 + 玻璃拟态 UI

## 构建

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 作者

[@Srysfu](https://github.com/Srysfu)

## 许可

仅供学习研究使用。
