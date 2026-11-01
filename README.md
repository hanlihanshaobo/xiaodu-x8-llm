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
- **打断**：思考中说话会立刻打断，改听新的一句
- **上下文**：断线重连后对话记忆还在
- **音乐**：接本地 Navidrome（Subsonic API），封面/歌词/进度齐全
- **本地优先**：ASR、LLM、TTS 都指向局域网服务，语音不出内网

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

推送到 GitHub 后由 Actions 自动构建，产物在 **Actions → build-apk → artifact「XiaoduLink」**。

本地构建（需要 JDK 17 + Android SDK）：

```bash
bash tools/build_apk.sh
```

首次打开会引导你关掉原生小度的麦克风权限，之后 App 才能独占麦克风。

### 3. 配置

所有可调项都在 `server/config.yaml`（不进仓库），模板见 `server/config.example.yaml`：

| 段 | 说明 |
|---|---|
| `wake` | 唤醒词、应答、超时 |
| `asr` | 语音识别（OpenAI 兼容的 `/v1/audio/transcriptions`） |
| `llm` | 大模型（OpenAI 兼容的 `/v1/chat/completions`） |
| `tts` | 语音合成（edge-tts / OpenAI 兼容 / 本地 VITS） |
| `music` | Navidrome 地址与账号、缓存策略 |
| `ha` | Home Assistant（可选） |

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
| `stop` / `audio_pause` / `audio_resume` | 播放控制 |

## 已知限制

- 播放音乐时麦克风关闭（半双工），期间不能语音唤醒，需点屏幕停止
- 需要一台常开的机器跑服务端；X8 只有 1GB 内存，跑不了模型
- 首次播放要从曲库下载整首歌到设备，约 2~5 秒

## 免责声明

本项目仅用于个人自有设备的研究与学习，与百度及其关联公司无关。
请勿用于商业用途，使用产生的任何后果由使用者自行承担。

## License

MIT
