## 通过播放音频文件的实际耗时，计算音频时钟误差

1. 在 `Download` 目录下放置如下文件：
    - `/storage/emulated/0/Download/test_60s.wav`
    - `/storage/emulated/0/Download/test_5min.wav`
    - `/storage/emulated/0/Download/test_10min.wav`
    - `/storage/emulated/0/Download/test_20min.wav`

2. 关于 **AudioTrack** 的使用：
    - 分为 `STREAM` 和 `STATIC` 两种模式
    - `STATIC` 模式相较于 `STREAM`，测量更精确
