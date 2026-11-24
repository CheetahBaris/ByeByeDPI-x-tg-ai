# Справка ciadpi (byedpi 17.x) для анализа прогонов

## Синтаксис позиций
offset[:repeats:skip][+flag]; offset<0 — от конца пакета.
Флаги: s=SNI, h=Host, e=конец, m=середина, n=не менять, r=random.
Примеры: -s 1+s, -d 3+s, -r -5+se, -s 1:3:5.

## Разрешённые опции (кратко)
Разрезание: -s split, -d disorder, -o oob, -q disoob, -r tlsrec.
Подделка: -f fake, -t ttl(3..12), -O fake-offset, -l fake-data, -n fake-sni, -Q fake-tls-mod.
Модификация: -M mod-http(h,d,r), -m tlsminor, -e oob-data.
Сеть: -X no-ipv6, -I conn-ip, -U no-udp, -N no-domain, -F tfo, -Y drop-sack, -g def-ttl.
Группы: -A auto(t,r,s,n,c), -L auto-mode(s,o,n), -T timeout, -R round.
Ограничения: -K proto(t,h,u,i), -H hosts, -j ipset, -V pf.
ЗАПРЕЩЕНО предлагать: -i, -p, -D, -E, -B, -C, -h, -v, -d1.
На Android без root не работают: -S (md5sig), -E (transparent).

## Классы ошибок curl
0 ok, 28 timeout (дроп), 35 tls (RST/подмена после ClientHello), 52 empty, 56 reset (активный сброс), 7 connect (движок не поднялся), 6 dns, 97 proxy (DNS/SOCKS).

## Диагностика
- timeout без RST → дроп по SNI/IP.
- reset/tls на всех TLS → блок ClientHello.
- ok но stub → блок HTTP/Host.
- Помогает только -f TTL 3..6 → фильтр по TTL (хрупко).
- Помогает только -r → DPI парсит TLS-запись целиком.
- 0 у всех, включая baseline → сломан стенд (VPN/порт/Wi-Fi), не стратегия.
- proxy только на части → DNS-блок, не про десинк.

## Факты по МегаФону (мобильная сеть, Xiaomi 13, Android)
- Рабочая формула 12/12: -X -I 0.0.0.0 -s1+s -d3+s -o2 -r-5+se -a1
- Устойчивые: -o1/-o3 вместо -o2; -r-6+se; -s2+s; + -f-1 -t3.
- -r-4+se хуже (9/12). Baseline — 17%.
- -r-5+se ключевой; -r1+s ломает (3/36).
- Без split/disorder -o+r дают 4/36.
- -d1 на мобильном = 0/36, таймауты. Никогда.
- TTL-техники (-d/-f с малым TTL) на этой линии дают 0% — проверять отдельно.
- -X обязателен: у телефона есть IPv6-адрес, но нет IPv6-маршрута; без -X часть доменов падает с ENETUNREACH.
- DNS-блок (не лечится десинком): telegram.org, web.telegram.org.
- Блок по IP (десинком не пробить): discord.com, x.com, rutracker.org, speedtest.net, часть googlevideo, linkedin, soundcloud.
- -f и -q в рабочих комбинациях ничего не добавляют.

## Методология
- Всегда baseline в раунде; сравнивай с ним, не с нулём.
- Меняй 1–2 переменные за раунд; ablation для вклада аргумента.
- Разница <10–15% — шум.
- Не повторяй уже проверенные стратегии.
- Не выдумывай цифры; мало данных → unknown/недостаточно.