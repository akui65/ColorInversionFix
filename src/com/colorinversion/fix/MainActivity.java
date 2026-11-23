package com.colorinversion.fix;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 极简状态页：仅用于说明模块信息、作用域与验证方式，无任何系统权限。
 */
public class MainActivity extends Activity {

    private static final String VERSION = "v1.1";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("颜色反转修复 " + VERSION);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.START);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView body = new TextView(this);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setTextColor(Color.DKGRAY);
        body.setLineSpacing(dp(4), 1f);
        body.setText(TextUtils.join("\n", new String[]{
                "",
                "作者：Hell'C",
                "",
                "作用域：系统框架 (android / system_server)",
                "",
                "作用：",
                "监听 Settings.Secure 的 accessibility_display_inversion_enabled，",
                "在开关变化、开机、用户切换时直接向 SurfaceFlinger",
                "写入反转颜色矩阵（1015 事务）；",
                "DisplayTransformManager 仅做状态同步。",
                "",
                "启用步骤：",
                "1. 打开 LSPosed 管理器，启用本模块；",
                "2. 在「作用域」中勾选「系统框架(android)」；",
                "3. 重启手机（或软重启 system_server）。",
                "",
                "验证方式：",
                "在系统设置中开启/关闭「颜色反转」，观察屏幕是否立即变化；",
                "也可在电脑上执行：",
                "adb logcat -s LSPosed-Bridge | findstr ColorInversionFix",
                "正常应看到 SF 1015 set/clear transact=true。",
                "",
                "已知限制：",
                "1. 直写 SurfaceFlinger 不叠加夜间模式/灰度矩阵合成；",
                "2. 已在努比亚 Z60 Ultra 领先版 + HyperOS 移植包",
                "   （Android 17）验证，其他 ROM 未验证；",
                "3. 需要 root + LSPosed。",
                "",
                "模块不申请任何权限，不修改系统分区。"
        }));
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);
        setTitle("颜色反转修复 " + VERSION);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
