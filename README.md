# xiaodu-x8-llm

把小度 X8（XDH-0F-A1）自带的语音助手换成自己部署的大模型。

保留 X8 的屏幕、四麦克风阵列和喇叭，只替换"大脑"和"嘴巴"：
唤醒 → 语音识别 → 大模型 → 语音合成，全程走局域网。

```
┌──────────────── 小度 X8 (Android) ────────────────┐
│  XiaoduLink.apk —— 常驻前台的语音客户端            │
│   · 唤醒词监听、状态环、字幕                        │
│   · 横屏播放界面：封面 / 滚动歌词 / 进度条 / 控制按钮 │
│   · WebSocket 上传麦克风音频，播放服务端下发的音频    │
└───────────────────────┬───────────────────────────┘
                        │ ws://<PC>:8765
┌───────────────────────┴───────────────────────────┐
│  服务端（Python）                                   │
│   VAD → ASR → 唤醒判定 → LLM → 分句 → TTS           │
│   音乐：Navidrome 曲库（可选 Home Assistant 控制）   │
└───────────────────────────────────────────────────┘
```

## 特点

- **不需要改固件**：设备刷任意带文件管理器的系统，装一个 APK 即可
- **唤醒词自己定**：默认「小度同学」，也兼容「小度小度」
- **中文识别**：服务端接 Qwen3-ASR-1.7B-8bit（oMLX / MLX），支持热词偏置；曲库同音字自动纠正（赵磊→赵雷、话→画）
- **放歌时也能唤醒**：客户端切到 `VOICE_COMMUNICATION` + 回声消除，只有 TTS 播报时才短暂闭麦
- **先理解再执行**：点歌、切歌、设备控制先让模型结合上下文解析意图，再调用工具；设备名不确定时先确认，避免误操作
- **打断**：思考中说话会立刻打断，改听新的一句
- **上下文**：断线重连后对话记忆还在
- **音乐**：接本地 Navidrome（Subsonic API），封面/歌词/进度齐全
- **本地优先**：ASR、LLM、TTS 都指向局域网服务，语音不出内网
- **在线更新**：APK 发布在 GitHub Releases，App 内可直接检查更新，支持 GitHub 加速前缀

## 目录

```
server/     服务端：WebSocket + VAD/ASR/LLM/TTS + 音乐 + HA
client/     Android 客户端（无第三方依赖，aapt2+d8 直接构建）
tools/      构建 APK、模拟设备联调、启动服务
docs/       部署与验收
```

## 快速开始

### 1. 服务端

```bash
pip install -r server/requirements.txt
copy server\config.example.yaml server\config.yaml
# 编辑 config.yaml：填 ASR / LLM 地址和 key、Navidrome 账号
python server/run.py
```

启动后会打印设备连接地址，形如 `http://192.168.1.10:8765/ws`。

### 2. 客户端

推送到 GitHub 后 Actions 自动构建，APK 发布到 Releases 的 `latest`；Actions artifact 仅作备份。

本地构建（需要 JDK 17 + Android SDK）：

```bash
bash tools/build_apk.sh
```

首次打开会引导你关掉原生小度的麦克风权限，之后 App 才能独占麦克风。

服务端跑起来后，也可以让设备直接打开 `http://<服务端IP>:8765/apk/XiaoduLink.apk` 下载最新 APK（需要本地 `dist/XiaoduLink.apk` 存在）。

### 3. 配置

所有可调项都在 `server/config.yaml`（不进仓库），模板见 `server/config.example.yaml`：

| 段 | 说明 |
|---|---|
| `wake` | 唤醒词、应答、超时 |
| `asr` | 语音识别（OpenAI 兼容的 `/v1/audio/transcriptions`） |
| `llm` | 大模型（OpenAI 兼容的 `/v1/chat/completions`） |
| `tts` | 语音合成（edge-tts / OpenAI 兼容 / 本地 VITS） |
| `music` | Navidrome 地址与账号、缓存策略 |
| `ha` | Home Assistant（可选；长期令牌填 `ha.token`） |

ASR 推荐在 oMLX 里加载 `Qwen3-ASR-1.7B-8bit`，中文和方言比 SenseVoice / Whisper turbo 稳；服务端会自动把当前歌曲、最近播放和家里设备名作为热词传给支持 prompt 的模型。

### 4. Docker（推荐）

镜像同时支持 `linux/amd64`、`linux/arm64`、`linux/arm/v7`（32 位 ARM），拉取时会自动选对应架构。仓库根目录的 `docker-compose.yml` 已经改成直接使用已发布镜像，不再本地构建：

```bash
mkdir -p config
docker run --rm --entrypoint cat ghcr.io/hanlihanshaobo/xiaodu-x8-llm:latest \
  /app/server/config.example.yaml > config/config.yaml
# 编辑 config/config.yaml，填 ASR / LLM / Navidrome / HA
docker compose pull
docker compose up -d
```

1Panel 用法：把 `docker-compose.yml` 放到 `/opt/1panel/docker/compose/xiaodu-brain/`，配置放在同目录的 `config/config.yaml`（也可以直接上传旧服务器的 `server/config.yaml`）。

HA 长期令牌直接填在 `config/config.yaml` 的 `ha.token`（这个文件不进 git，挂载时只读，不会上传）。

`docker-compose.yml` 默认 `network_mode: host`：设备 UDP 自动发现和音频地址都正常。如果 host 网络不能用，把 `network_mode: host` 注释掉，取消注释文件里的 `ports`（8765 / 8766）即可；同时必须把 `config.yaml` 里的 `server.public_url` 改成 `http://<Docker宿主机IP>:8765`，并且设备端建议手动填服务器地址（bridge 下 UDP 广播通常过不了）。

> 如果挂载时报 `not a directory` / `Are you trying to mount a directory onto a file`，说明 `config.yaml` 没准备好，Docker 把路径先建成了目录。先按上面的命令生成 `config/config.yaml` 再启动。
>
> 如果 `docker pull` 提示 denied，先在宿主机 `docker login ghcr.io`，或在 GitHub 的 Packages 设置里把镜像设为 public。

### 5. 更新

App 内「设置 → 检查更新」会查 GitHub 最新 Release，下载后直接调起安装：

```text
https://github.com/hanlihanshaobo/xiaodu-x8-llm/releases/tag/latest
```

下载慢时可在设置里选 GitHub 加速前缀（gh-proxy / ghfast / ghproxy / 自定义），只影响 APK 下载，不影响语音链路。

## 通信协议

设备 → 服务端

| 内容 | 说明 |
|---|---|
| 二进制 | 16kHz / s16le / 单声道 PCM |
| `{"type":"hello"}` | 握手 |
| `{"type":"command","text":"下一首"}` | 界面按钮指令 |
| `{"type":"speak_done","id":"..."}` | 一句播完 |
| `{"type":"log","text":"..."}` | 崩溃/异常上报 |

服务端 → 设备

| 内容 | 说明 |
|---|---|
| `state` | idle / listening / thinking / speaking |
| `asr` / `reply` | 字幕 |
| `speak` | 播放一段 TTS |
| `music` | 播放一首歌（带标题、歌词、封面） |
| `preload` | 下一首提前下载到设备 |
| `stop` / `stop_speech` / `audio_pause` / `audio_resume` | 播放控制 |

## 已知限制

- 播放音乐时仍监听唤醒词（切到带回声消除的采集源）；TTS 播报时短暂闭麦
- Docker 用 bridge 网络时必须设置 `server.public_url`，否则设备会拿到 `172.x` 容器地址
- 需要一台常开的机器跑服务端；X8 只有 1GB 内存，跑不了模型
- 首次播放要从曲库下载整首歌到设备，约 2~5 秒

## 路线图

- [ ] **视频接入 Emby**：复用现有的播放器与字幕 UI，增加视频解码与全屏播放
- [ ] 断点续播、播放列表管理
- [x] 播放中也能唤醒（AEC 采集 + 服务端 ASR）
- [x] Docker 多架构镜像（amd64 / arm64 / armv7）

## 免责声明

本项目仅用于个人自有设备的研究与学习，与百度及其关联公司无关。
请勿用于商业用途，使用产生的任何后果由使用者自行承担。

## License

MIT
