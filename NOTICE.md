# 来源与许可

ShadeReply 采用 GNU General Public License v3。[许可原文](LICENSE) · [源码与各版本标签](https://github.com/Xinhuaaa/shadereply/tags) · [构建说明](README.md#构建)

| 组件 | 来源与许可 |
| --- | --- |
| WeKite | 微信通知、头像、回复、已读、媒体接口定位和表情映射参考 SymonChu 与 WeKite 贡献者的实现，基于提交 `19bffe4c`，并适配 ShadeReply 的设置和通知流程。GPL-3.0。[源码与许可](https://github.com/SymonChu/WeKite/tree/19bffe4c) |
| COUI17 | 使用 `7135f75aa4346fa03e589e0e41235386f309bdd8` 的 COUI17 源码构建设置页。主体为 Apache-2.0，来自 COUI-Kit 的标注文件适用 MPL-2.0。[固定版本源码](https://github.com/Costben/coui17/tree/7135f75aa4346fa03e589e0e41235386f309bdd8) · [版权声明](licenses/COUI-NOTICE.txt) · [依赖的版权及许可](https://github.com/Costben/coui17/blob/7135f75aa4346fa03e589e0e41235386f309bdd8/THIRD-PARTY-LICENSES.md) |
| DexKit 2.2.0 | Copyright © LuckyPray。用于按特征定位微信的方法。Android 接口为 Apache-2.0，随 APK 分发的 C++ Core 为 LGPL-3.0。[2.2.0 源码](https://github.com/LuckyPray/DexKit/tree/ffa6c51) · [Core 源码与许可](https://github.com/LuckyPray/DexKit/tree/ffa6c51/Core) |
| libxposed 102.0.0 | API 声明用于编译，Service 库随 APK 分发。Apache-2.0。[API 源码](https://github.com/libxposed/api) · [Service 源码](https://github.com/libxposed/service) |
| AndroidX 与 Kotlin | AndroidX、Kotlin 标准库和 Kotlin 协程采用 Apache-2.0。[AndroidX 源码](https://android.googlesource.com/platform/frameworks/support/) · [Kotlin 源码](https://github.com/JetBrains/kotlin) · [协程源码](https://github.com/Kotlin/kotlinx.coroutines) |

许可原文包括 [GNU GPL v3](LICENSE)、[Apache License 2.0](licenses/Apache-2.0.txt)、[GNU LGPL v3](licenses/LGPL-3.0.txt) 和 [Mozilla Public License 2.0](licenses/MPL-2.0.txt)。

构建脚本依赖 Maven Central 的 `org.luckypray:dexkit:2.2.0`。如需使用修改后的 DexKit 验证改动，可按该版本上游的构建说明生成 AAR，在本地构建时将此依赖替换为该 AAR，再用自己的密钥签名生成的 APK。[DexKit 构建说明](https://github.com/LuckyPray/DexKit/tree/ffa6c51)
