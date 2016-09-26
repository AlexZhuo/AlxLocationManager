# AlxLocationManager
Android GPS+基站+WiFi热点多渠道定位的Demo，使用Google Geo Location API，请翻墙后测试效果

Demo功能

1、在没有开启GPS功能或限制了定位权限时，可以使用WiFi或者基站进行定位（收集Wifi和基站信息并发送到谷歌提供的API接口，Demo中自带临时KEY一个，需翻墙使用）

2、在安装有谷歌框架的手机中使用谷歌框架中的gcm location进行硬件定位，更省电，如果没有安装谷歌框架，则切换到安卓原生定位方式

3、对于多个传感器收集的定位结果进行择优选用

4、两种不同的定位策略可供选用，普通APP使用低电量策略，地理型APP使用高精度策略，高精度策略的采样更频繁，移动触发率更高

5、每2s刷新UI显示定位结果，并记录上次定位时间和定位精度

6、天朝境内的硬件经纬度自动转换为火星坐标系

Demo效果

![demo](https://github.com/AlexZhuo/AlxLocationManager/blob/master/demo1.png)
