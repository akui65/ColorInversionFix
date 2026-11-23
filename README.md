# 颜色反转修复（LSPosed 模块）

修复 HyperOS 移植包（Android 16/17）上**系统设置里开启「颜色反转」无效果**的问题。已在
「努比亚 Z60 Ultra 领先版 + 小米 14 HyperOS 移植包（Android 17）」实机验证。

## 模块信息

| 项目 | 值 |
| --- | --- |
| 名称 | 颜色反转修复 |
| 包名 | `com.colorinversion.fix` |
| 版本 | v1.1（versionCode 2） |
| 作者 | **Hell'C** |
| 作用域 | 系统框架（`android` / system_server） |
| 适用 | Android 16/17 的 HyperOS 移植包；已在 Android 17 实机验证 |
| 依赖 | root（SukiSU/KernelSU 等）+ LSPosed |

## 根因

实机排查结论：

1. `Settings.Secure.accessibility_display_inversion_enabled` 写入正常（0/1 正确）；
2. `ColorDisplayService` 正常运行，观察者能回调，`DisplayTransformManager.setColorMatrix(300, 矩阵)` 调用返回成功；
3. 但 SurfaceFlinger 始终收不到反转矩阵（`dumpsys SurfaceFlinger` 只有色彩模式矩阵），画面不反；
4. 用 `service call SurfaceFlinger 1015` 直接写矩阵会立即反色，说明 **SF/HWC 通路正常，断点在 DTM 下发段**。

因此 v1.1 不再依赖 DTM 下发，改为直接向 SurfaceFlinger 写 1015。

## 功能

- 自行监听 `accessibility_display_inversion_enabled`，不依赖 ROM 内部观察者；
- 开关变化、开机、用户切换时直接写 SurfaceFlinger：
  - 开启：`i32 1` + 16 个 float（矩阵反射读取框架 `MATRIX_INVERT_COLOR`）；
  - 关闭：`i32 0`（仅清除模块自己写过的矩阵）；
- `DisplayTransformManager` 仅做状态同步，保留 hook 纠正异常参数；
- 日志统一前缀 `[ColorInversionFix]`，关键行：`SF 1015 set/clear transact=...`、`applyState(...) sfWritten=...`。

## 安装

1. 到本仓库 **Releases** 下载 `ColorInversionFix-LSPosed-v1.1.apk`（tag `v1.1`）。
2. 安装（HyperOS 的 USB 安装限制会使 `adb install` 报 `INSTALL_FAILED_USER_RESTRICTED`，推荐 root 方式）：

   ```bash
   adb push ColorInversionFix-LSPosed-v1.1.apk /data/local/tmp/
   adb shell su -c "pm install -r -t /data/local/tmp/ColorInversionFix-LSPosed-v1.1.apk"
   ```

3. 打开 LSPosed 管理器 → 模块 → 启用「颜色反转修复」。
4. 作用域勾选 **系统框架（android）**。
5. 重启手机。

## 验证

```bash
adb logcat -s LSPosed-Bridge | findstr ColorInversionFix
```

开启「颜色反转」后应看到：

```
[ColorInversionFix] SF 1015 set len=16 transact=true
[ColorInversionFix] applyState(settings-observer ...) enabled=true sfWritten=true ...
```

关闭后应看到 `SF 1015 clear transact=true`，画面恢复。完整实机证据见
[`docs/verification-2026-11-23.txt`](docs/verification-2026-11-23.txt)。

## 构建

要求：JDK 11+、Android SDK `build-tools/35.0.0` 与 `platforms/android-35`、Python 3。

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
python verify_apk.py ..\..\outputs\ColorInversionFix-LSPosed-v1.1.apk
```

构建链：`javac` → `d8` → `aapt2` → 写入 `classes.dex` → `zipalign` → `apksigner`（v1+v2）。
首次构建会自动生成 `debug.keystore`（仅本地调试签名，已被 `.gitignore` 排除）。

## 已知限制

1. 直写 SurfaceFlinger **不叠加**夜间模式/灰度的矩阵合成；开启反转期间其他颜色变换可能被覆盖；
2. 仅在上述指定设备/ROM 验证，其他 ROM 未验证；
3. 需要 root + LSPosed；不修改 system/vendor 分区。

## 回滚

- LSPosed 管理器中关闭模块 → 重启（立即失效）；
- 或卸载 APK → 重启；
- 若屏幕颜色异常，重启即可恢复（SF 矩阵不持久化）。

## 作者

**Hell'C**

## 许可

暂未指定开源许可证；如需使用/分发，请先联系作者。
