# HAR-on-Smartphone
development an Android APP to implement human activity recognition on smartphone in real time

开发平台:Andrioid Studio3.0

实现功能：

调用手机三轴加速度计实时采集三轴加速度数据

然后将数据输入之前保存好的已训练好的深度学习的人体姿态识别模型中进行人体姿态识别（.pb文件，模型保存方法https://github.com/zhangzhao156/CNN-for-HAR-on-WISDM-Dataset）

将模型的识别结果显示在手机该APP界面上

显示结果格式：

[当前时间]Gesture is detected:识别动作
