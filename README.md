Hi!

This is a Flutter+Android app for the Carlinkit-CPC200-CCPA CarPlay adapter.

Based on the https://github.com/rhysmorgan134/node-CarPlay project.

##  Features

The basic Autolink.apk app works very poorly on my car head unit. It always disconnects and never reconnects again. This is the reason why I started this project. In my app, if any error happens, the app shows the last screen (losing the map view while using navigation is very annoying, so I save the last screen) and tries to reconnect as quickly as possible.

##  Getting started

You can use release.apk on your Android device with the connected Carlink USB dongle.

##  Usage

This app rarely disconnects. It happens when not all data from the dongle has been read within a short period of time. So, I hope someone can help solve this. Also, this app does not support internal audio (but it is easy to implement).

Enjoy and feel free to write to me with any ideas, pull requests, or issues!


##  ------------------------


Привет!

Это приложение Flutter+Android для адаптера Carlinkit-CPC200-CCPA CarPlay.

Основано на проекте https://github.com/rhysmorgan134/node-CarPlay.

## Особенности

Стандартное приложение Autolink.apk работает очень плохо на головном устройстве моего автомобиля. Оно постоянно отключается и не переподключается снова. Именно поэтому я начал этот проект. В моем приложении, если возникает ошибка, оно показывает последний экран (потеря карты при использовании навигации очень раздражает, поэтому я сохраняю последний экран) и пытается переподключиться как можно быстрее.

## Начало работы

Вы можете использовать release.apk на своем Android-устройстве с подключенным Carlink USB-донглом.

## Использование

Это приложение редко отключается. Это происходит, когда не все данные от донгла были прочитаны за короткий период времени. Надеюсь, кто-то поможет решить эту проблему. Также в этом приложении нет поддержки внутреннего аудио (но это легко реализовать).

Наслаждайтесь и не стесняйтесь писать мне свои идеи, присылать запросы на изменения или сообщать о проблемах!



## License

MIT License

Copyright (c) 2024 Alekander Bukharskii

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
