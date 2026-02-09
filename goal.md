制作一个 Android 端 APP，用于定时查询教务系统是否有新成绩发布；当有新成绩发布时，发布一条系统通知提示用户。

网址：https://jw.ustc.edu.cn/for-std/grade/sheet，该网址当未完成登录时会自动跳转到 https://jw.ustc.edu.cn/login?refer=https://jw.ustc.edu.cn/for-std/grade/sheet，且完成登录后会跳转到 https://jw.ustc.edu.cn/home，此时需要重新选择登录到 https://jw.ustc.edu.cn/for-std/grade/sheet。

需要有本地加密存储用户名和密码的机制。

在 APP 本地缓存当前的成绩状态，并与远程的成绩状态作对比，当有成绩增加或减少时，更新成绩状态，并发送提示。
