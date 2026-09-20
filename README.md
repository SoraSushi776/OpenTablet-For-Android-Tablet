# OpenTablet For Android Tablet

将 **Android 平板** 的触控 / 触控笔输入，通过 USB HID Gadget 转成 **OpenTabletDriver（OTD）** 可识别的数位板信号，使 Mac（或其它跑 OTD 的主机）把平板当作外接绝对定位数位板使用。

```
┌─────────────┐   MotionEvent    ┌──────────────────┐   /dev/hidg0   ┌──────────────┐
│ Android 平板 │ ───────────────► │ Kotlin UI + JNI  │ ─────────────► │ 主机 OTD     │
│ 触控 / 笔    │                  │ C++ 映射 & 写入   │   USB HID      │ 数位板驱动    │
└─────────────┘                  └──────────────────┘                └──────────────┘
```

---

## 功能特性

- 将触控笔 / 手指输入映射为 **0–32767** 绝对坐标 + **0–8191** 压感
- **悬停（hover）**：笔在空中移动时主机光标跟随（in-range + 压感 0）
- **落笔稳定**：tip 状态机 + 队列合并，避免光标闪烁
- **坐标映射预设**：16:9 / 16:10 / 4:3，支持缩放 % 与偏移 %，主页实时预览，可保存自定义预设
- **快捷键面板**：撤销 / 重做 / 画笔 / 橡皮 / 笔刷大小 / 空格 / Tab，以及 Ctrl / Shift / Alt 修饰键
- **键盘 HID**（`/dev/hidg1`）：配合 OTD 快捷键绑定
- **一键导出 OTD 配置 JSON**，方便导入主机端 OpenTabletDriver

---

## 工作原理

### 1. USB HID Gadget

App 在 **Root** 权限下通过 **ConfigFS** 创建 USB HID Gadget：

| 设备节点 | 用途 | 报文长度 |
|---------|------|---------|
| `/dev/hidg0` | 数位板（Digitizer / Pen） | 8 字节 |
| `/dev/hidg1` | 标准键盘 | 8 字节 |

挂载流程（主页「挂载 HID 设备」）：

1. 停止 `adbd`，设置 `sys.usb.config=none`，避免系统把 USB 抢回 ADB
2. Unbind UDC，清理旧 function 配置
3. 写入 HID Report Descriptor，绑定 UDC
4. `chmod 666` 设备节点；SELinux 设为 Permissive（退出时恢复）

### 2. 数位板报文（8 字节）

与本工程配套、且在本机 OTD 上验证可用的布局：

| 字节 | 含义 |
|------|------|
| 0 | 状态：`bit0` = tip（笔尖/感应），`bit1` = 侧键，`bit2` = 橡皮 |
| 1–2 | X：`uint16` 小端，`0–32767` |
| 3–4 | Y：`uint16` 小端，`0–32767` |
| 5–6 | 压感：`uint16` 小端，`0–8191` |
| 7 | 保留 |

**悬停包**：`bit0 = 1`（in-range），压感 `0`，坐标有效。  
**落笔包**：`bit0 = 1`，压感 `> 0`。  
**抬笔包**：`bit0 = 0`，压感 `0`。

> 注意：不同 OTD 版本 / 配置对 `TabletReportParser` 的字节偏移理解可能不同。若你主机端光标乱飞，请先确认 OTD 配置与上表一致，或改用与本工程相同的配置导出文件。

### 3. 坐标映射

触控坐标是 **右侧触控 View** 的本地坐标。映射公式：

1. 按目标比例（如 16:9）在 View 内求 **最大内接矩形**
2. 乘以 **缩放 %**（100% = 尽量撑满 View）
3. 再按 **偏移 X/Y %**（相对 View 宽高）平移区域原点
4. 区域内的触控归一化到 `0–32767`；区域外 clamp 到边缘

```
HID_X = clamp((touchX - originX) / regionWidth,  0, 1) * 32767
HID_Y = clamp((touchY - originY) / regionHeight, 0, 1) * 32767
```

内置预设：

| 名称 | 比例 | 缩放 | 偏移 |
|------|------|------|------|
| 16:9 · 100% | 16:9 | 100% | 0, 0（居中撑满） |
| 16:10 · 100% | 16:10 | 100% | 0, 0 |

用户可在主页调整后命名保存；启动数位板时会带上当前预设，触控区显示蓝色映射框。

### 4. 事件与写入管线（防闪烁）

```
onHoverEvent / onTouchEvent
        │  仅推送当前点（不刷 historical）
        ▼
TouchProcessor.pushPoint()     // UI 线程，非阻塞
        │  mutex 小队列：同 tip 合并，保留 tip 边沿
        ▼
writer 线程 writeWithRetry()   // EAGAIN 重试，失败回队
        ▼
/dev/hidg0
```

要点：

- **落笔期间屏蔽 hover**，避免 tip=0 / tip=1 交替导致光标闪烁
- 抬笔后短暂静默 hover，滤掉部分机型的尾包
- 队列不再使用会在满时由生产者改 `tail` 的 lock-free 环（曾导致坐标撕裂）
- 写失败不丢 tip 边沿；退出时在最后坐标发 tip-up

---

## 环境与配置需求

### Android 平板

| 项目 | 要求 |
|------|------|
| 系统 | Android 12L+（`minSdk 32`） |
| 权限 | **Root**（`su`），用于 ConfigFS / HID Gadget / SELinux |
| USB | 支持 **USB Device / Gadget**（能出现 UDC，如 `/sys/class/udc/`） |
| 连接 | USB 线连接主机；调试时可能需先开 ADB，挂载后 App 会停 `adbd` |
| 输入 | 推荐支持触控笔（`TOOL_TYPE_STYLUS`）；手指可在数位板界面打开「手指输入」 |

### 主机（以 Mac + OTD 为例）

| 项目 | 要求 |
|------|------|
| 驱动 | [OpenTabletDriver](https://github.com/OpenTabletDriver/OpenTabletDriver)（建议 0.6.x） |
| 配置 | 导入 App 导出的 `AndroidTablet.json`，或手工匹配 VID/PID |
| 标识 | VID `0x5541`（21825），PID `0x0001` |
| Parser | `OpenTabletDriver.Plugin.Tablet.TabletReportParser` |
| 报文长度 | `InputReportLength = 8` |
| 数位板范围 | `MaxX/MaxY = 32767`，`MaxPressure = 8191` |

**OTD 侧建议**：

- 输出模式：绝对定位（Absolute），输出区域覆盖整块屏幕
- 笔尖绑定：优先用 **压感阈值（Threshold / Pressure）** 触发左键，悬停（压感 0）不会误点
- 若悬停不跟笔：检查是否把状态 `bit0` 绑成了必须按下的按键；可改为压感触发，或对照上文报文表调整

### 构建环境（开发者）

| 项目 | 要求 |
|------|------|
| JDK | 17+（本仓库 CI/本地曾用 JDK 25 构建通过） |
| Android SDK | `compileSdk 37`，见 `local.properties` 中 `sdk.dir` |
| NDK / CMake | NDK（工程 `.cxx` 曾用 28.x），CMake **3.22.1** |
| Gradle | 使用仓库内 `gradlew` |

```bash
# 构建 Debug APK
./gradlew :app:assembleDebug

# 产物
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 使用步骤

### 平板端

1. 安装 APK，授予 Root  
2. 打开 App → **挂载 HID 设备**（看到 `/dev/hidg0`「已就绪」）  
3. 在 **坐标映射预设** 中选择比例 / 缩放 / 偏移，可保存预设  
4. （可选）**导出 OTD 配置文件**，传到主机  
5. **启动数位板** → 进入触控采集界面  
6. 用笔在右侧蓝色框内书写；左侧为快捷键与开关  

### 主机端（Mac）

1. 安装并运行 OpenTabletDriver  
2. 将 `AndroidTablet.json` 放入 OTD 的 `Configurations` 目录（或通过 UI 导入）  
3. USB 连接平板，完成平板端挂载后 OTD 应识别到设备  
4. 设置 Tablet Area / Output Area 与笔绑定  
5. 在绘图软件中测试悬停、落笔、压感、四角位置  

### 退出

- 数位板界面 → **退出数位板**  
- 主页 → **取消挂载并恢复 ADB**（便于重新插线调试）

---

## 工程结构

```
OpenTablet-For-Android-Tablet/
├── app/src/main/
│   ├── cpp/
│   │   ├── hid_driver.cpp/.h       # HID 描述符与 /dev/hidg* 写入
│   │   ├── touch_processor.cpp/.h  # 映射、队列、writer 线程
│   │   └── native-lib.cpp          # JNI
│   ├── java/.../
│   │   ├── MainActivity.kt         # 状态、挂载、映射预设、启动
│   │   ├── TabletActivity.kt       # 数位板模式、快捷键
│   │   ├── mapping/                # MappingMath / MappingPreset
│   │   ├── view/TouchCaptureView   # 触控采集 + hover/落笔状态机
│   │   └── view/MappingPreviewView # 主页映射预览
│   └── res/layout/                 # activity_main / activity_tablet
├── otd_config/AndroidTablet.json   # OTD 配置参考
└── outline.md                      # 早期设计大纲
```

---

## 常见问题

**Q: `/dev/hidg0` 不存在？**  
A: 确认设备有 UDC、已 Root；先「挂载 HID 设备」。部分 ROM 需解锁 USB ConfigFS。挂载过程会停 ADB，调试请预留无线调试或挂载后再操作。

**Q: OTD 识别不到设备？**  
A: 主机设备管理器 / `system_profiler SPUSBDataType`（Mac）查看是否有 VID `5541` PID `0001`。确认 OTD 配置已加载且 `InputReportLength=8`。

**Q: 落笔光标闪烁？**  
A: 使用本仓库较新版本（事件状态机 + 队列合并）。若仍闪，抓 logcat 中 `TabletHidDigitizer` 标签反馈。

**Q: 悬停光标不动？**  
A: 确认 App 触控区有黄色 hover 指示；OTD 笔尖建议改为压感阈值绑定。若主机只认「状态 bit0=1 才更新位置」，本工程悬停已带 in-range 位。

**Q: 指针乱飞 / 范围不对？**  
A: 多半是 OTD 报文布局与本工程不一致，或映射预设比例/偏移与预期不符。可先恢复内置「16:9 · 100%」，再对照报文表检查 OTD Parser。

**Q: 挂载后无法 adb？**  
A: App 会停止 adbd 以免系统改回 ADB 模式。在 App 中「取消挂载并恢复 ADB」，或 `su -c 'setprop sys.usb.config adb; start adbd'`。

---

## 安全与限制

- 需要 **Root**，挂载阶段会 **临时关闭 SELinux Enforcing** 与 **adbd**，退出时尽量恢复；请仅在自用设备上运行  
- 不提供任意内核提权或绕过锁机能力；USB Gadget 依赖设备内核已支持 ConfigFS HID  
- 触控笔压感依赖硬件上报；无压感设备落笔时按满压或实报压感处理  

---

## License

请在发布到 GitHub 前自行补充开源协议（如 MIT / Apache-2.0）。若暂未选择，可先在仓库设置中保持 Private。

---

## 致谢

- [OpenTabletDriver](https://github.com/OpenTabletDriver/OpenTabletDriver)
- Android ConfigFS USB Gadget / Linux HID Gadget 驱动
