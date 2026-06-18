
# DUI 语音助手分析

## 关键信息
- SDK: DUI-lite-android-sdk-CAR_v1.4.9-51-g2d03166
- 产品 ID: 279605823
- 设备 ID: LDC973B42S3020865
- 云端 NLU: wss://dds.dui.ai/dds/v3/prod
- 管理平台: https://www.dui.ai (思必驰 DUI 开放平台)

## musicControlPause 失败原因
- 语音助手本地判断 audioType 为空 → 拒绝暂停
- audioType 来自 CarCabin (GroupId:2 ChannelId:11) 的媒体状态
- 同样参数下 musicControlPlay 成功，说明 play 有额外 fallback

## 可能的解决方案
1. **DUI 平台配置**: 登录 www.dui.ai → 产品 279605823 → 技能配置 → 媒体控制
   - 可能可以添加或修改支持的媒体源包名
   - 可能可以调整 audioType 的判断逻辑
2. **车机 CarCabin 配置**: 注册 com.cusc.media 为合法媒体源
3. **本地 hack**: 如果 DUI 平台不可用，hook StateManager 的 musicControlPause 方法

## 语音"播放"成功的逻辑
- musicControlPlay 直接调用了 MediaSession.getTransportControls().play()
- 不依赖 audioType 判断
