package com.hanxing.mifit;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 佳明账号入口。已登录时只显示状态与退出；未登录才出现账号密码输入框。 */
public class GarmUi {

    public static void menu(final Activity a, final Runnable onDone) {
        final Context ctx = a;
        final boolean logged = Garm.hasToken(ctx) || Garm.canAutoLogin(ctx);
        final Dialog[] holder = new Dialog[1];

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);

        if (logged) {
            body.addView(Ui.dlgTitle(ctx, "佳明账号"));
            body.addView(Ui.dlgBody(ctx, (Garm.hasToken(ctx) && Garm.tokenValid(ctx))
                    ? "已登录佳明中国，可以直接上传运动记录。"
                    : "凭证已过期，下次推送会用保存的账号或授权自动续期。"));

            // 自动登录（记住账号密码）
            LinearLayout auto = new LinearLayout(ctx);
            auto.setOrientation(LinearLayout.HORIZONTAL);
            auto.setGravity(Gravity.CENTER_VERTICAL);
            auto.setPadding(0, Ui.dp(ctx, 12), 0, 0);
            auto.addView(Ui.tv(ctx, "过期自动登录", 13, Ui.TXT, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            final TextView autoState = Ui.tv(ctx, Vault.on(ctx) ? "开" : "关", 13,
                    Vault.on(ctx) ? Ui.ORANGE : Ui.SUB, true);
            autoState.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 2), Ui.dp(ctx, 8));
            autoState.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (Vault.on(ctx)) {
                        Vault.forget(ctx);
                        autoState.setText("关");
                        autoState.setTextColor(Ui.SUB);
                        Toast.makeText(ctx, "已清除记住的账号", Toast.LENGTH_SHORT).show();
                    } else {
                        holder[0].dismiss();
                        form(a, onDone, "记住账号",
                                "输入一次佳明账号密码，以后凭证过期会自动重新登录。");
                    }
                }
            });
            auto.addView(autoState);
            body.addView(auto);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(ctx, 16), 0, 0);

            TextView out = Ui.tv(ctx, "退出登录", 13, Ui.ORANGE, true);
            out.setGravity(Gravity.CENTER_VERTICAL);
            out.setPadding(Ui.dp(ctx, 2), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12));
            out.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Garm.saveToken(ctx, "");
                    Vault.forget(ctx);
                    Toast.makeText(ctx, "已退出佳明账号", Toast.LENGTH_SHORT).show();
                    holder[0].dismiss();
                    if (onDone != null) onDone.run();
                }
            });
            row.addView(out, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView close = Ui.btn(ctx, "关闭", false);
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { holder[0].dismiss(); }
            });
            row.addView(close);
            body.addView(row);
        } else {
            form(a, onDone, "登录佳明中国", "填佳明中国的账号密码，登录后即可上传运动记录。");
            return;
        }

        holder[0] = Ui.panel(ctx, body);
        holder[0].show();
    }

    /** 账号密码表单：登录成功即保存 token，按勾选记住账号 */
    private static void form(final Activity a, final Runnable onDone,
                            String title, String desc) {
        final Context ctx = a;
        final Dialog[] holder = new Dialog[1];

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(Ui.dlgTitle(ctx, title));
        body.addView(Ui.dlgBody(ctx, desc));

        final EditText eu = Ui.field(ctx, "佳明账号（邮箱）");
        final EditText ep = Ui.field(ctx, "密码");
        ep.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = Ui.dp(ctx, 13);
        body.addView(eu, flp);
        LinearLayout.LayoutParams flp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp2.topMargin = Ui.dp(ctx, 9);
        body.addView(ep, flp2);

        final boolean[] remember = {true};
        LinearLayout rem = new LinearLayout(ctx);
        rem.setOrientation(LinearLayout.HORIZONTAL);
        rem.setGravity(Gravity.CENTER_VERTICAL);
        rem.setPadding(0, Ui.dp(ctx, 12), 0, 0);
        rem.addView(Ui.tv(ctx, "记住账号（过期自动重新登录）", 13, Ui.TXT, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView remState = Ui.tv(ctx, "开", 13, Ui.ORANGE, true);
        remState.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 2), Ui.dp(ctx, 8));
        remState.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                remember[0] = !remember[0];
                remState.setText(remember[0] ? "开" : "关");
                remState.setTextColor(remember[0] ? Ui.ORANGE : Ui.SUB);
            }
        });
        rem.addView(remState);
        body.addView(rem);

        final TextView tip = Ui.tv(ctx, "", 12, Ui.ORANGE, false);
        tip.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        body.addView(tip);

        body.addView(Ui.btnRow(ctx, "取消", "登录",
                new Runnable() {
                    public void run() { holder[0].dismiss(); }
                },
                new Runnable() {
                    public void run() {
                        final String u = eu.getText().toString().trim();
                        final String p = ep.getText().toString();
                        final boolean rem2 = remember[0];
                        if (u.length() == 0 || p.length() == 0) {
                            tip.setText("账号密码都要填");
                            return;
                        }
                        tip.setText("登录中…");
                        new Thread(new Runnable() {
                            public void run() {
                                try {
                                    final String tok = Garm.login(u, p);
                                    if (tok == null || tok.length() < 20) throw new Exception("没拿到登录凭证");
                                    Garm.saveToken(ctx, tok);
                                    if (rem2) Vault.remember(ctx, u, p);
                                    else Vault.forget(ctx);
                                    a.runOnUiThread(new Runnable() {
                                        public void run() {
                                            holder[0].dismiss();
                                            Toast.makeText(ctx, "已登录佳明", Toast.LENGTH_SHORT).show();
                                            if (onDone != null) onDone.run();
                                        }
                                    });
                                } catch (final Exception e) {
                                    a.runOnUiThread(new Runnable() {
                                        public void run() {
                                            tip.setText("失败：" + e.getMessage());
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                }));

        holder[0] = Ui.panel(ctx, body);
        holder[0].show();
    }
}
