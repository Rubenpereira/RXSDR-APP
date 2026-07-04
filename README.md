# RXSDR-APP

**Receptor SDR para Android (somente RX)** — por **PU1XTB Ruben, Araruama/RJ**

Receptor de radio definido por software que roda inteiramente no celular ou TV Box Android.
Conecta a um dongle **RTL-SDR pelo USB (cabo OTG)** ou a um servidor **rtl_tcp pela rede**
(PC, Raspberry Pi, TV Box) e faz todo o processamento no proprio aparelho: espectro,
cachoeira, demodulacao e audio.

## Recursos

- Modos: **AM, USB, LSB, CW, NFM e WFM** (radio FM)
- Panadapter (espectro) com zoom por pinca e sintonia por arrasto
- Cachoeira com velocidade e contraste ajustaveis
- S-meter analogico de ponteiro (estilo Yaesu) + barra de LEDs + leitura em dBm
- **NR** — redutor de ruido por subtracao espectral para HF
- Squelch, AGC ou ganho RF manual (tabela de ganhos por tuner)
- VFO A/B com SWAP, passo de 10 Hz a 1 MHz, entrada direta de frequencia
- HF abaixo de 24 MHz por amostragem direta automatica (dongle v3 ou modificado)
- Audio continua em segundo plano com a tela apagada (servico de primeiro plano)
- Botao TELA ATIVA para manter o display sempre aceso
- Manual de operacao completo dentro do app (botao MANUAL)
- Compativel com celulares e TV Box Android 7+ (use mouse/air mouse na TV)

## Instalacao (usuarios)

1. Baixe o **[RXSDR-APP.apk](RXSDR-APP.apk)** e instale (permita "fontes desconhecidas").
2. **USB**: ligue o dongle RTL-SDR no cabo OTG. O app pede para instalar o driver gratuito
   ["RTL-SDR driver"](https://play.google.com/store/apps/details?id=marto.rtl_tcp_andro)
   na primeira vez. Escolha **USB** e aperte **ON**.
   *Nao inicie o stream manualmente no driver — o RXSDR-APP faz isso sozinho.*
3. **Rede**: em um PC/Raspberry/TV Box com o dongle, rode
   `rtl_tcp -a 0.0.0.0 -p 1234 -s 1024000`, escolha **TCP** no app,
   digite `IP:1234` e aperte **ON**.

Leia o [MANUAL_DE_OPERACAO.txt](MANUAL_DE_OPERACAO.txt) ou toque no botao **MANUAL** dentro do app.

## Licenca

[GPL-3.0](LICENSE) — Copyright (C) Ruben Pereira (PU1XTB)

**73 de PU1XTB — Araruama, RJ, Brasil**
