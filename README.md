# transcritor-ata

Aplicação desktop para transcrever gravações de reuniões (`.wmv`, `.mp4`, `.mkv`, `.avi`, entre
outros formatos suportados pelo ffmpeg) e gerar automaticamente uma **ata em `.docx` com
aparência profissional**, pronta para envio a clientes.

Feita para **usuários não técnicos**: a interface é toda em português, com um verificador de
instalação que explica passo a passo o que falta configurar.

## Público-alvo

Equipes que gravam reuniões em vídeo e precisam transformar essas gravações em atas escritas,
sem precisar editar vídeo, usar linha de comando ou conhecer ferramentas de IA.

## Como funciona (visão geral do fluxo)

1. Escolha o arquivo de vídeo da reunião.
2. Escolha o motor de transcrição (Whisper, recomendado, ou Vosk).
3. Clique em "Transcrever". O programa extrai o áudio, transcreve e gera a ata.
4. Ao final, abra a ata `.docx` diretamente pela própria aplicação.
5. Opcionalmente, habilite a geração de uma **ata estruturada com IA** (resumo executivo,
   participantes, pauta, decisões e tabela de ações), usando a API da Anthropic (Claude).

## Pré-requisitos (Windows 11)

A aplicação verifica tudo isso automaticamente ao abrir (menu **Ajuda → Verificar instalação**),
mostrando links e instruções para o que estiver faltando. Os requisitos são:

### 1. ffmpeg

Necessário para extrair o áudio dos vídeos. Instale pelo Terminal/PowerShell:

```
winget install Gyan.FFmpeg
```

Alternativa: baixe em https://www.gyan.dev/ffmpeg/builds/ e adicione a pasta `bin` ao PATH.

> Após instalar pelo winget, feche e reabra o transcritor-ata para que o PATH atualizado seja
> reconhecido.

### 2. whisper.cpp (motor de transcrição recomendado)

Baixe o binário pré-compilado para Windows nas releases oficiais:

https://github.com/ggml-org/whisper.cpp/releases

Procure o arquivo `.zip` com sufixo `-bin-x64`, extraia em uma pasta de sua preferência e, nas
preferências do transcritor-ata, aponte o campo do executável para o `whisper-cli.exe` extraído.
**Não é necessário compilar nada.**

### 3. Um modelo Whisper (`.bin`)

Baixe em: https://huggingface.co/ggerganov/whisper.cpp/tree/main

- `ggml-medium.bin` — recomendado, bom equilíbrio para uso em CPU.
- `ggml-small.bin` — para máquinas mais modestas.
- `ggml-large-v3.bin` — para quem tem GPU disponível.

Salve o arquivo e selecione-o nas preferências da aplicação.

### 4. (Alternativa offline leve) Vosk

Caso prefira o motor Vosk em vez do Whisper, baixe um modelo em português em
https://alphacephei.com/vosk/models (recomendado: `vosk-model-small-pt-0.3`), descompacte e
selecione a pasta nas preferências.

### 5. (Opcional, pago) Chave da API da Anthropic

Só é necessária se você quiser usar o recurso de **ata estruturada com IA**. Sem ela, o programa
funciona 100% offline. Obtenha uma chave em https://console.anthropic.com — o uso é cobrado pela
Anthropic conforme o consumo. Configure via variável de ambiente `ANTHROPIC_API_KEY` ou no campo
correspondente das preferências.

### 6. (Opcional, experimental) Identificação de participantes

Para que a transcrição indique quem falou cada trecho (`Pessoa 1`, `Pessoa 2`, ...), baixe o
arquivo `lium_spkdiarization-8.4.1.jar` em
https://git-lium.univ-lemans.fr/Meignier/lium-spkdiarization , salve-o em uma pasta e selecione-o
no campo "LIUM_SpkDiarization" das preferências. Requer Java instalado (o mesmo usado para rodar o
programa). Marque então o checkbox "Identificar participantes na transcrição (experimental)".

> A identificação usa a ferramenta clássica LIUM (não neural): a qualidade é limitada e funciona
> melhor em áudios com poucos participantes e pouca sobreposição de falas. Ela roda em paralelo com
> a transcrição e, se falhar, a ata é gerada normalmente, apenas sem os rótulos de locutor.

## Compilar e executar

Requer **JDK 21** e **Maven**.

```
mvn package
java -jar target/transcritor-ata.jar
```

O jar gerado (`target/transcritor-ata.jar`) já inclui todas as dependências (fat-jar).

> No Windows, o SWT roda normalmente na thread principal. A flag `-XstartOnFirstThread` só é
> necessária no macOS — não se aplica ao uso normal desta aplicação no Windows.

## Importar no Eclipse

`File → Import → Maven → Existing Maven Projects`, selecione a pasta do projeto. O m2e resolve
as dependências automaticamente, incluindo o profile do SWT correto para o seu sistema
operacional (Windows por padrão).

## Estrutura do projeto

- `gui` — janela principal e diálogos SWT.
- `audio` — extração de áudio via ffmpeg e execução de processos externos.
- `transcription` — motores de transcrição (Whisper.cpp, Vosk) e o pipeline orquestrador.
- `minutes` — geração das atas em `.docx` (Apache POI).
- `ai` — integração opcional com a API da Anthropic para a ata estruturada.
- `diarization` — identificação opcional de participantes (LIUM_SpkDiarization).
- `deps` — verificador de dependências.
- `config` — preferências do usuário.

## Onde ficam os dados do usuário

- Configuração: `%APPDATA%\transcritor-ata\config.properties`
- Logs: `%APPDATA%\transcritor-ata\logs\`

## Limitações conhecidas

- A identificação de participantes (diarização) é opcional e experimental, baseada na ferramenta
  clássica LIUM — bem menos precisa que soluções neurais modernas. Veja o item 6 dos pré-requisitos.
- Não há instalador (`.msi`/`.exe`); a distribuição é via jar executável.
- Os estilos da ata são definidos em código (`DocxMinutesGenerator`), sem uso de um template
  `.dotx` corporativo — a classe já foi estruturada para essa evolução futura.
- O motor Vosk é mais leve, porém menos preciso que o Whisper, e não gera pontuação tão rica.
- A geração de ata estruturada com IA depende de conexão com a internet e de uma chave de API
  paga da Anthropic.

## Testes

```
mvn test
```

Os testes não exigem ffmpeg, whisper.cpp, modelos ou uma chave de API real — processos externos
e a integração com IA são isolados atrás de interfaces e testados com mocks/fixtures.
