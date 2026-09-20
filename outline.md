# 项目名称: TabletHidDigitizer (OpenTabletDriver 兼容版)

# 项目目标:
创建一个 Android App，将安卓平板的屏幕触控转化为符合 OpenTabletDriver (OTD) 规范的 HID 数位板信号，通过 /dev/hidg0 实时写入，使 Mac 端的 OpenTabletDriver 驱动能够直接识别并接管该平板，实现极低延迟的外接数位板功能。

---

## 1. 架构设计与整体工作流

项目由三部分组成：
1. **Kotlin UI 层 (App 控制端)**：
    - Root 权限校验与 /dev/hidg0 挂载。
    - 配置 VID/PID 与传输采样率。
    - 界面上提供“导出 OTD 配置文件 (.json)”功能，方便一键导入 Mac 端 OpenTabletDriver。
2. **Native C++ 层 (高频数据打包与写入引擎)**：
    - 抓取平板屏幕触控（MotionEvent / evdev）。
    - 将坐标数据打包为标准的 8 字节 OTD 格式数据包（包含 Status, X, Y, Pressure）。
    - 以极低延迟（120Hz ~ 240Hz+）无锁异步写入 `/dev/hidg0`。
3. **Mac 端 OpenTabletDriver 配置**：
    - 提供匹配该 HID 描述符与报文格式的 OTD Tablet JSON 配置文件。

---

## 2. 目录结构与关键文件大纲

TabletHidDigitizer/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── cpp/
│   │   │   │   ├── CMakeLists.txt              # NDK 编译配置
│   │   │   │   ├── native-lib.cpp              # JNI 接口导出
│   │   │   │   ├── hid_driver.cpp / .h         # OTD 描述符生成与 /dev/hidg0 二进制写入
│   │   │   │   └── touch_processor.cpp / .h    # 绝对坐标映射算法与高频写入线程
│   │   │   ├── java/com/example/tablethiddigitizer/
│   │   │   │   ├── MainActivity.kt             # 主界面：显示连接状态、OTD JSON 导出按钮
│   │   │   │   ├── utils/RootUtils.kt          # 执行 Root 命令、挂载 ConfigFS 脚本
│   │   │   │   └── service/DigitizerService.kt # 后台前台服务（保证触控映射在后台持续运行）
│   │   │   ├── res/layout/activity_main.xml    # 控制台 UI
│   │   │   └── AndroidManifest.xml             # 声明权限与 Service
│   └── build.gradle.kts                        # NDK 模块关联
└── otd_config/
└── AndroidTablet.json                      # 供 Mac 端 OpenTabletDriver 读取的设备定义文件

---

## 3. 核心协议与报文定义

### 3.1 USB VID / PID 与 HID 描述符
- **Vendor ID (VID)**: `0x5541` (自定义)
- **Product ID (PID)**: `0x0001` (自定义)
- **HID Report Descriptor (厂商自定义输入报告)**:
  采用 Raw Vendor-Defined HID 描述符，避免 Mac 系统自带驱动干扰，完全交由 OTD 接管：
  `0x06, 0x00, 0xFF, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x02, 0x09, 0x02, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x08, 0x81, 0x02, 0xC0`

### 3.2 OTD 8 字节二进制报文结构 (Report ID = 0x02)
Standard OTD Packet Structure (8 Bytes):
- **Byte 0**: `0x02` (Report ID)
- **Byte 1**: `Status Flags`
    - Bit 0: Tip Switch / 笔尖触控状态 (1 = 按压/Touch Down, 0 = 抬起/Touch Up)
    - Bit 1: Barrel Switch / 侧键 1 (可选，预留 0)
    - Bit 2: Eraser / 橡皮擦 (可选，预留 0)
- **Byte 2 - 3**: `X 轴绝对坐标` (uint16_t，小端序，范围 0 ~ 32767)
- **Byte 4 - 5**: `Y 轴绝对坐标` (uint16_t，小端序，范围 0 ~ 32767)
- **Byte 6 - 7**: `Pressure 压感值` (uint16_t，小端序，范围 0 ~ 8191；若屏幕不支持压感，下笔时默认填 8191，抬笔填 0)

---

## 4. 各模块详细功能需求

### 4.1 挂载与环境准备模块 (`RootUtils.kt` & ConfigFS 脚本)
- 检查 `/dev/hidg0` 是否存在，若不存在使用 Root 权限挂载：
    1. `mkdir /config/usb_gadget/g1/functions/hid.usb0`
    2. 写入上述自定义 OTD Report Descriptor
    3. `echo 0 > protocol`, `echo 0 > subclass`, `echo 8 > report_length`
    4. 解绑并重新绑定 UDC (`/config/usb_gadget/g1/UDC`)
    5. 执行 `chmod 666 /dev/hidg0` 允许 C++ 免 Root 读写

### 4.2 C++ 低延迟数据打包写入引擎 (`hid_driver.cpp` & `touch_processor.cpp`)
- 监听触控屏高频点（使用 `MotionEvent.getHistoricalX/Y()` 抓取硬件补帧数据）。
- 将触控屏坐标映射归一化为 $0 \sim 32767$ 的整数。
- 将坐标与触控状态打包为 8 字节 OTD 报文，直接写入 `/dev/hidg0` 文件描述符。

### 4.3 Mac 端 OpenTabletDriver 配置文件 (`AndroidTablet.json`)
Agent 需在工程中自动生成以下 JSON 文件，用户导入 Mac 版 OTD 的 `Configurations` 目录后即可瞬间识别：

```json
{
  "Name": "Android Virtual Digitizer",
  "Vid": 21825,
  "Pid": 1,
  "DigitizerIdentifiers": [
    {
      "VendorID": 21825,
      "ProductID": 1,
      "InputReportLength": 8,
      "ReportID": 2
    }
  ],
  "Specifications": {
    "Digitizer": {
      "Width": 327.67,
      "Height": 327.67,
      "MaxX": 32767,
      "MaxY": 32767,
      "MaxPressure": 8191
    }
  },
  "Parser": "TabletDriver.Plugin.Attributes.Parsers.Vendor.VendorReportParser"
}