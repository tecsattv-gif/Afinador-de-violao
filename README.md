# Afinador de violão

App Android para afinar o violão pelo microfone do celular, na afinação padrão (E A D G B E).

**Versão atual: 1.1.0**

### Novidades da 1.1.0
- Seletor de **sensibilidade** (Baixa / Média / Alta): toque no botão para trocar.
  Baixa ignora ruído de fundo e reage só à corda tocada; Alta pega sons fracos.
- Leitura mais estável: saltos súbitos (ruído, harmônicos, oitava errada) são ignorados
  até se repetirem 3 vezes seguidas; mediana de 7 leituras e média móvel dos cents.
- Ponteiro mais suave.
- "Afinado!" só aparece depois de ~0,3 s dentro de ±5 cents ("Quase lá…" antes disso).

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
- Sensibilidade do microfone: Baixa, Média (padrão) ou Alta.
- O app guarda o último modo, a calibração e a sensibilidade.
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
  com filtro de saltos, mediana das últimas 7 leituras e média móvel para estabilizar o ponteiro.

## Créditos

Luiz Alberto Cardoso — tecsattv@gmail.com
