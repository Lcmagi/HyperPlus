# HyperPlus 自用 Fork

Lcmagi 基于 213DEE 的 HyperPlus 为小米 18 Fold 调整了半自动旋转按钮和设置界面，供个人使用。修改位于 [fold/rotation-ui-power 分支](https://github.com/Lcmagi/HyperPlus/tree/fold/rotation-ui-power)。模块的完整功能、LSPosed 作用域和使用方法见[原作者的中文说明](https://github.com/213DEE/HyperPlus/tree/e5fc108d783c4cccf38ecb21c28fd40b509762d8#中文)。

**下载测试版**　[下载 ZIP，解压后安装其中的 APK](https://github.com/Lcmagi/HyperPlus/actions/runs/44720652501/artifacts/14621637353) · [对应源码](https://github.com/Lcmagi/HyperPlus/tree/61d6e8d120a4db4fe88fd993a48a80617cd69c16)

## 分支改动

- **旋转按钮大小**　按当前屏幕短边调整按钮直径。以 440 dpi 为例，外屏约从 54 dp 缩至 48 dp，内屏从 62 dp 缩至 58 dp。按钮继续缩小时，可点击区域仍至少为 48 dp。
- **折叠时的位置**　按钮显示期间连续收到配置变化时，合并重复的定位请求，并在折叠动画结束后再校正一次。按钮消失时取消待执行的回调。
- **倒计时重绘**　原版在按钮可见时每个显示帧都刷新倒计时环。此分支约每 33 毫秒更新一次，减少高刷新率下的重绘次数，超时仍按实际经过的时间计算。
- **设置页布局**　主页与通用子页在宽窗口中将内容居中，最大宽度为 640 dp。窄窗口沿用可用宽度。

## 验证

[单元测试及 APK 构建已通过](https://github.com/Lcmagi/HyperPlus/actions/runs/44720652501)。尺寸测试覆盖 370、400 和 440 dpi 下的内外屏差异，以及窄屏的最小点击区域。折叠换屏后的按钮落点、点击转向和整机耗电仍待小米 18 Fold 真机验证。

本分支于 2026-11-20 基于[上游提交 e5fc108](https://github.com/213DEE/HyperPlus/commit/e5fc108d783c4cccf38ecb21c28fd40b509762d8) 修改，沿用 [GNU Affero General Public License v3.0](LICENSE)。
