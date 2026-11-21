# Afinador de violão

App Android para afinar o violão pelo microfone do celular, na afinação padrão (E A D G B E).

**Versão atual: 1.5.0**

### Novidades da 1.5.0
- **Importar songbook com títulos automáticos**: ao importar, escolha "Ler o PDF e separar cada música".
  O app lê o texto do PDF, encontra onde começa cada música (título no topo da página, "Tom:", artista),
  pega o **título** e marca se é **Música** (tem linhas de acordes) ou **Aula**.
  - Antes de separar, aparece a lista para conferir: corrigir títulos, desmarcar, trocar Música/Aula
    e **⤒ Juntar** com a de cima (quando uma música foi cortada em duas).
  - Escolha o gênero das músicas (ou crie um novo) e a categoria das aulas; opção de guardar também o PDF completo.
  - Cada música vira um PDF próprio, mantendo o texto e a qualidade originais.
  - PDFs escaneados (só imagem) não têm texto: o app avisa e guarda inteiro para separar com ✂ no leitor.
- **Zoom manual** no leitor: **pinça com dois dedos** (100% a 400%) e **toque duplo** para alternar 100% / 200%.
- **✂ Separar** no leitor agora mantém o texto original do PDF (antes virava imagem).

### Novidades da 1.4.0
- Novas abas **Aulas** e **Cifras** (biblioteca de PDFs guardada só no celular):
  - **Aulas** separadas por **categorias** e **Cifras** separadas por **gêneros**, criados por você
    (toque longo numa categoria/gênero para renomear ou apagar);
  - **＋ Importar PDF** (vários de uma vez) com opção de **separar automaticamente pelo nome**:
    nomes com "aula", "teoria", "tutorial", "escala", "harmonia", "acorde", "exercício"… vão para Aulas,
    o resto vai para Cifras;
  - busca pelo nome; toque longo num PDF para renomear, **mover** (inclusive entre Aulas e Cifras) ou apagar;
  - **Backup**: exporta toda a biblioteca num `.zip` e restaura sem apagar o que já existe.
- **Leitor de PDF** dentro do app:
  - zoom de 100% a 400% (botões −/+, pinça com dois dedos ou toque duplo), **rolagem automática** com velocidade ajustável (para tocar sem as mãos);
  - guarda a última página e o zoom de cada arquivo; a tela fica ligada;
  - **✂ Separar**: escolha "da página X até Y", se é música (cifra) ou aula/tutorial e o gênero/categoria
    — o app cria um PDF novo só com essas páginas no lugar certo (ideal para songbooks que misturam tudo).
- Os PDFs não vão para o GitHub: ficam só no celular.

### Novidades da 1.3.0
- Pentatônicas com **som**: toque numa bolinha para ouvir a nota (som de corda dedilhada,
  gerado no próprio app, na altura certa da corda e casa). A bolinha acende ao tocar.
- Botão **▶ Tocar shape**: toca as notas do shape subindo e descendo, acendendo cada bolinha.
  No "Braço todo", toca duas oitavas a partir da tônica mais grave.
- Botão do bordão com texto mais curto (não fica mais cortado).

### Novidades da 1.2.0
- Nova aba **Pentatônicas** (as abas "Afinador" e "Pentatônicas" ficam no topo):
  - as 12 tônicas (Dó a Si), **maior** e **menor**;
  - os **5 shapes** (shape 1 = tônica na 6ª corda) e a opção **Braço todo**;
  - braço da casa 0 à 17, que rola para os lados e vai sozinho até o shape escolhido;
  - laranja = tônica, verde-água = notas do shape, cinza = resto da escala;
  - notas da escala com os graus e a escala relativa (ex.: Lá menor = Dó maior);
  - botão para **inverter**: Mi bordão em cima ou embaixo.
- O microfone desliga enquanto estiver na aba Pentatônicas (poupa bateria).
- O app guarda a última aba e as escolhas das pentatônicas.

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

- Kotlin nativo; única biblioteca externa: [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) para ler o texto dos PDFs e separar páginas.
- Detecção de frequência pelo algoritmo YIN (44,1 kHz, janela de 4096 amostras),
  com filtro de saltos, mediana das últimas 7 leituras e média móvel para estabilizar o ponteiro.

## Créditos

Luiz Alberto Cardoso — tecsattv@gmail.com
