# DUI 语音暂停/搜索问题 — 完整分析

## 根因
语音助手 `musicControlPause` 被调用（确认识别到了 `com.cusc.media`），
但内部判断 `audioType` 为空 → 放弃操作 → TTS "当前场景不支持"。

而 `musicControlPlay` 在相同参数下成功，说明 play 有 fallback 逻辑，
pause 没有。

## audioType 来源
从 `StateManager.dealMediaState` 日志看出，audioType 来自车机 IPC 通道
`GroupId:2 ChannelId:11` 的 reply，该通道可能是 CarCabin 媒体管理系统。
CarCabin 有包名→audioType 的映射表，`com.cusc.media` 不在表中。

## 已尝试的 hack（均无效）
- PlaybackState extras 注入 `audioType="music"`
- MediaMetadata 注入 `AUDIO_TYPE` / `CONTENT_TYPE`
- MEDIA_BUTTON broadcast 接收
- activeQueueItemId 设置

## 关键数据
- DUI productId: 279605823
- 设备: LDC973B42S3020865
- SDK: DUI-lite-android-sdk-CAR_v1.4.9
- 云端: wss://dds.dui.ai/dds/v3/prod
- 管理后台: www.dui.ai / www.duiopen.com
- 音乐技能文档 ID: ct_skill_Music / yinyue_wpcr

## 解决方案优先级
1. **DUI 平台配置** (最可能): 登录 dui.ai → 产品 279605823 → 
   音乐技能配置 → 添加 `com.cusc.media` 为受支持的媒体源包名
2. **CarCabin 配置**: 修改车机系统文件，注册新媒体源
3. **APK 逆向修改**: 反编译语音助手 APK，修改 musicControlPause 的判断逻辑
4. **包名伪装**: 将 PSA 插件包名改为已被识别的包名（但签名验证可能成问题）
