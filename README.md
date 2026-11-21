# Afinador de violão

App Android para afinar o violão pelo microfone do celular, na afinação padrão (E A D G B E).

**Versão atual: 1.0.0**

## Funções

- **Auto**: reconhece sozinho qual corda está a tocar (a mais próxima) e a destaca.
- **Corda fixa**: escolha 6ª Mi, 5ª Lá, 4ª Ré, 3ª Sol, 2ª Si ou 1ª Mi para afinar só aquela.
- **Cromático**: mostra qualquer nota (Dó, Dó♯, Ré…) com a oitava.
- Mostrador com ponteiro de −50 a +50 cents:
  - verde: afinado (até ±5 cents);
  - amarelo: perto (até ±15 cents);
  - vermelho: longe.
- Dica na tela: "Grave · aperte" ou "Agudo · afrouxe".
- Frequência em Hz.
- Calibração do Lá de 430 a 450 Hz (padrão 440 Hz).
- O app guarda o último modo e a calibração.
- A tela fica ligada enquanto o afinador está aberto.
- Funciona offline, em retrato ou paisagem, no Android 5.0 ou mais novo.

## Como usar

1. Abra o app e permita o uso do microfone.
2. Toque uma corda de cada vez, deixando-a soar.
3. Aperte a tarraxa se estiver grave e afrouxe se estiver agudo, até o ponteiro ficar verde no centro.

## Download

O APK é compilado automaticamente pelo GitHub Actions a cada envio para a branch `main`
e fica em **Releases** (`Afinador-vX.Y.Z.apk`).

O APK é sempre assinado com a mesma chave (`app/afinador.keystore`), então as
atualizações instalam por cima sem perder as configurações.

## Técnica

- Kotlin nativo, sem bibliotecas externas.
- Detecção de frequência pelo algoritmo YIN (44,1 kHz, janela de 4096 amostras),
  com mediana das últimas 5 leituras para estabilizar o ponteiro.

## Créditos

Luiz Alberto Cardoso — tecsattv@gmail.com
