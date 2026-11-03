# Bot de música para Discord com importação de playlists por imagem (OCR)

*Especificação técnica · rascunho v0.1 · 29 de outubro de 2026*

## 1. Resumo

Bot de Discord em Java que toca música em canais de voz a partir de comandos simples (`@bot p <link ou nome>`) e tem um diferencial: ao ser marcado numa mensagem com imagem (um print de playlist, por exemplo), ele lê o texto por OCR, extrai a lista de músicas, mostra uma prévia para confirmação e coloca tudo na fila. Não é preciso criar playlist nem adicionar faixa por faixa.

O projeto tem três eixos técnicos: (1) um bot de música (JDA + Lavaplayer, com a criptografia de voz DAVE); (2) um pipeline imagem → texto → faixas (validação, pré-processamento, Tesseract, parser); (3) segurança, já que o bot processa arquivos e textos não confiáveis enviados por qualquer usuário.

Escopo da v1: uso privado ou em poucos servidores, um único processo, sem banco de dados. Os fatos externos citados (versões, políticas, estado do YouTube) foram verificados em 29/10/2026 e mudam rápido; vale reconferir antes de implementar.

## 2. Escopo e premissas

**Objetivos**

- Tocar música em canal de voz por menção, slash command e menu de contexto.
- Importar faixas de uma imagem, com prévia e confirmação do usuário.
- Manter o núcleo (OCR, parser, regras) testável sem Discord.
- Segurança defensiva desde o início (seção 8) e entregas em fases pequenas (seção 11).

**Fora do escopo (v1)**

- Tocar direto de Spotify, Apple Music ou Deezer (DRM e termos de uso): o bot só lê nomes nos prints e procura em fontes de áudio acessíveis.
- Bot público em larga escala, monetização, banco de dados, painel web, sharding, filtros de áudio, letras.
- Garantia de OCR perfeito.

**Premissas**

- Alvo: Linux x64 e JDK 25 (limites atuais da JDAVE, seção 4).
- Poucos servidores (menos de 100), sem intents privilegiados.
- Desenvolvimento solo, com pouca experiência prévia em bots de Discord.

## 3. Requisitos

### 3.1 Funcionais

| ID | Requisito | Prioridade |
| --- | --- | --- |
| RF01 | Entrar no canal de voz do usuário e tocar link, nome ou playlist | Must |
| RF02 | Fila por servidor: adicionar, pular, parar, pausar, listar, remover item, sair | Must |
| RF03 | Comando por menção `@bot p <consulta>` (equivale a `/play`) | Must |
| RF04 | `@bot` + imagem: OCR → lista de faixas → prévia → confirmação → fila | Must |
| RF05 | Prévia com remoção de itens, aviso de baixa confiança e cancelamento | Must |
| RF06 | Menu de contexto “Tocar músicas da imagem”, para imagens em outras mensagens | Should |
| RF07 | Busca tolerante a erros de OCR, com pontuação de similaridade | Should |
| RF08 | Mais de um layout de print (Spotify, YouTube Music, lista simples) | Should |
| RF09 | Mensagens de erro claras em pt-BR e limites configuráveis | Must |

### 3.2 Não funcionais

- **Segurança:** ver seção 8.
- **Latência:** confirmar a interação em menos de 3 s (limite do Discord) e entregar a prévia em cerca de 10 s para um print típico (meta a calibrar).
- **Robustez:** falha numa faixa não derruba a importação; OCR e áudio rodam com timeout.
- **Privacidade:** nenhuma imagem persistida; logs sem conteúdo do usuário.
- **Manutenção:** dependências fixadas e núcleo sem dependência do JDA.

## 4. Stack e decisões

| Camada | Escolha | Observações |
| --- | --- | --- |
| Linguagem | Java 25 (LTS) | Exigido pela JDAVE (usa a API de funções externas do Java) |
| Build | Maven ou Gradle | A decidir |
| Discord | JDA 6.x | Mais recente encontrada: 6.5.0. O JDA pode quebrar compatibilidade em versões minor: fixar a versão e ler as notas de cada release |
| Voz (E2EE) | JDAVE | DAVE é obrigatório desde 01/03/2026; o README lista só Linux x64; projeto jovem |
| Áudio | Lavaplayer 2.x (fork da Lavalink-Devs) + youtube-source (módulo v2) | Embutido no processo na v1; Lavalink fica para depois (conferir suporte a DAVE) |
| OCR | Tesseract 5.x (`por`, `eng`) como processo externo | Alternativa: Tess4J (wrapper JNA). O processo externo pode ser morto por timeout e isola crashes nativos |
| Imagens | `javax.imageio` | OpenCV (JavaCV) só se o pré-processamento pedir |

Notas:

- O ImageIO do JDK não lê WebP: a v1 aceita PNG e JPEG (WebP depois, com um plugin como o TwelveMonkeys).
- O código nativo da JDAVE pode exigir `--enable-native-access=ALL-UNNAMED` ao iniciar a JVM (conferir no README).

### 4.1 Intents e acesso às imagens

O bot usa só `GUILD_MESSAGES` e `GUILD_VOICE_STATES`, ambos não privilegiados. Sem o intent privilegiado `MESSAGE_CONTENT`, mensagens que mencionam o bot continuam chegando com conteúdo e anexos, e interações (slash e menu de contexto) entregam a mensagem alvo completa. Assim não há verificação do Discord, exigida para intents privilegiados a partir de 100 servidores. Consequências: comandos com prefixo (`!play`) ficam de fora, e ao responder a uma imagem antiga marcando o bot os anexos da mensagem respondida podem não vir (a validar em teste), por isso o menu de contexto.

## 5. Arquitetura

### 5.1 Visão geral

```
Discord ──► discord/ (JDA: menção, slash, contexto, botões)
                │
                ▼
          application/ (casos de uso) ◄──► security/ (política de anexo, rate limit)
         ┌─────┼───────────┐
         ▼     ▼           ▼
       ocr/  parser/     source/ ──► audio/ ──► JDA AudioManager ──► voz (DAVE)
```

### 5.2 Módulos

| Pacote | Responsabilidade |
| --- | --- |
| `discord` | Adaptadores do JDA: listeners, comandos, botões, formatação das mensagens |
| `application` | Casos de uso (`ImportFromImage`, `PlayQuery`) que orquestram os módulos |
| `security` | `AttachmentPolicy`, `RateLimiter`, `InputSanitizer` |
| `ocr` | `OcrEngine`, `ImagePreprocessor`, `TesseractCliEngine` |
| `parser` | `TrackLineParser`, `NoiseFilter`, `Normalizer` |
| `source` | `TrackResolver` (YouTube hoje, outras fontes depois) |
| `audio` | `GuildMusicManager`, `TrackScheduler`, `AudioPlayerSendHandler` |

Regra de dependência: só `discord` (e o `SendHandler` de `audio`) conhecem o JDA. `ocr`, `parser`, `security` e `source` não importam nada dele.

### 5.3 Contratos principais

```java
interface OcrEngine { OcrResult read(byte[] png, OcrOptions opts) throws OcrException; }
record OcrWord(String text, int left, int top, int width, int height, float conf, int line) {}
record OcrResult(List<OcrWord> words, Duration elapsed) {}

interface TrackLineParser { List<ParsedTrack> parse(OcrResult ocr); }
record ParsedTrack(String raw, String title, String artist, double confidence) {}

interface TrackResolver { CompletableFuture<Optional<ResolvedTrack>> resolve(ParsedTrack track); }
```

### 5.4 Fluxo principal (menção + imagem)

Os nomes de API do JDA abaixo devem ser conferidos na versão usada.

1. `MessageReceivedEvent` em servidor: ignorar bots e mensagens que não mencionam o bot.
2. Pré-checagens: autor num canal de voz, cooldown e permissões `VOICE_CONNECT` e `VOICE_SPEAK` do bot.
3. Selecionar até 3 anexos de imagem; `AttachmentPolicy` valida tipo, tamanho e dimensões informados pelo Discord.
4. Responder “lendo a imagem…” para dar retorno imediato.
5. Baixar o anexo pelo proxy do Discord (`Attachment#getProxy()`), com limite de bytes e timeout.
6. Conferir os bytes iniciais (PNG ou JPEG), ler as dimensões pelo cabeçalho, decodificar com `ImageIO` e recodificar como PNG normalizado.
7. `ImagePreprocessor` e `OcrEngine` (processo externo, timeout, semáforo global).
8. `TrackLineParser` gera os `ParsedTrack` (máximo de 25 por importação).
9. Criar a sessão de importação (TTL de 5 min, dono = autor) e enviar a prévia, com menções desligadas e markdown escapado, e os botões “Tocar tudo”, “Remover itens” (select menu) e “Cancelar”.
10. Ao confirmar (só o dono): resolver faixa a faixa com concorrência limitada, enfileirar e conectar ao canal de voz.
11. Relatório final: quantas foram enfileiradas e quantas não foram encontradas.

**Variações.** No menu de contexto: `MessageContextInteractionEvent` → `deferReply(true)` → mesmo pipeline a partir do passo 3, com `event.getTarget()`. Em `/play` e `@bot p`, links só de hosts permitidos e texto livre vira `ytsearch:`. Erros comuns (nenhuma faixa reconhecida, fila cheia, fora do canal de voz, timeout do OCR) devolvem mensagens curtas e específicas.

**Concorrência.** Handlers do JDA nunca bloqueiam. OCR e resolução rodam em executores próprios (no máximo 2 OCRs simultâneos), a fila por servidor é thread-safe e tudo tem timeout.

## 6. Pipeline de OCR e parsing

### 6.1 Pré-processamento

Cada etapa liga e desliga por configuração e é calibrada com um conjunto de prints reais.

1. Decodificar com limite de pixels e remover transparência.
2. Reduzir se o maior lado passar de cerca de 3000 px e ampliar 2× se for pequeno (a documentação do Tesseract recomenda por volta de 300 DPI).
3. Converter para escala de cinza.
4. Inverter se a luminância média indicar fundo escuro (comum em apps de música).
5. Ajustar contraste ou binarizar (Otsu), testando com e sem.

### 6.2 Execução do Tesseract

Comando: `tesseract stdin stdout -l por+eng --psm 6 tsv` (testar também `--psm 4`). Executado com `ProcessBuilder` e lista de argumentos (sem shell), imagem pela entrada padrão, leitura limitada da saída, `waitFor(timeout)` e `destroyForcibly()` se estourar. `OMP_THREAD_LIMIT=1` no ambiente do processo evita disputa de CPU. O formato TSV devolve posição, tamanho e confiança por palavra, que o parser aproveita. No Arch: `sudo pacman -S tesseract tesseract-data-por tesseract-data-eng`.

### 6.3 Parsing

O parser tenta estratégias e escolhe a de maior pontuação:

- **Duas linhas** (título em cima, artista embaixo; padrão dos apps): agrupar linhas pelo ritmo vertical; a linha com glifos maiores é o título.
- **Uma linha** `Artista - Título` ou `Título - Artista` (separadores `-`, `–`, `—`, `|`): a ordem é ambígua, então buscar as duas.
- **Lista numerada:** remover prefixos como `1.` ou `1)`.
- **Linha livre:** cada linha útil vira uma consulta.

Filtros de ruído: durações (`3:45`), contadores de reproduções, textos de interface em pt e en (“Adicionar”, “Baixado”, “Aleatório”), símbolos soltos e linhas de baixa confiança. Normalização: Unicode NFKC, remoção de caracteres de controle e de largura zero, espaços colapsados, limite de 100 caracteres, descarte de trechos que pareçam URL e deduplicação. A confiança de cada faixa combina a confiança média das palavras com a força da estrutura encontrada.

### 6.4 Resolução de faixas

A consulta (artista + título) vai para `ytsearch:`, com `scsearch:` como reserva. Entre os 5 primeiros resultados, escolher por similaridade textual (Jaro-Winkler ou Levenshtein) com o texto lido, duração plausível (configurável) e penalização de “live”, “cover”, “remix” e similares quando não estiverem na consulta. Abaixo de um limiar, a faixa é marcada como não encontrada ou de baixa confiança.

### 6.5 Limites esperados

Fundos com capas de álbum ou gradientes, fontes decorativas, baixa resolução, emojis e alfabetos não latinos (exigem modelos extras como `jpn`, `kor` e `rus`). Mitigações: confirmação do usuário, busca tolerante a erros e, no futuro, um modelo de visão via API atrás da interface `OcrEngine` (mais preciso, mas com custo e envio das imagens a terceiros).

## 7. Áudio e voz

- Um `AudioPlayerManager` único, com fontes registradas **explicitamente**: YouTube via youtube-source e, opcionalmente, SoundCloud. Nunca registrar a fonte HTTP genérica nem a local (seção 8).
- `GuildMusicManager` por servidor: `AudioPlayer`, `TrackScheduler` (fila de até 200 itens) e `AudioPlayerSendHandler` (`canProvide`, `provide20MsAudio`, `isOpus` verdadeiro).
- Conexão com `guild.getAudioManager().openAudioConnection(canal)`; desconectar após 5 min ocioso ou quando o canal esvaziar.
- DAVE: sem uma implementação configurada, a conexão de voz falha. Esboço (conferir assinaturas na versão usada; o trecho de `AudioModuleConfig` segue o README da JDAVE):

```java
JDA jda = JDABuilder.createLight(token, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_VOICE_STATES)
    .enableCache(CacheFlag.VOICE_STATE)
    .setAudioModuleConfig(new AudioModuleConfig()
        .withDaveSessionFactory(new JDaveSessionFactory()))
    .build();
```

- O YouTube é a peça mais frágil: relatos de setembro de 2026 apontam quebras por mudanças no streaming (SABR), corrigidas só em builds de snapshot. O README do youtube-source descreve opções como OAuth, poToken e cipher remoto; avaliar os riscos para a conta antes de usar. Tratar falhas por faixa com mensagem ao usuário e manter a fonte atrás de `TrackResolver`.

## 8. Segurança

**Fronteira de confiança.** Tudo que vem de fora é não confiável: bytes e metadados do anexo, texto das mensagens, texto do OCR e títulos e autores devolvidos pela busca (podem conter markdown ou menções).

### 8.1 Modelo de ameaças

| Ameaça | Vetor | Mitigação |
| --- | --- | --- |
| Imagem maliciosa ou bomba de descompressão | Arquivo enorme, corrompido ou com dimensões absurdas | Limites de bytes e pixels (lidos do cabeçalho), `ImageIO` + recodificação, OCR em processo isolado com timeout |
| Esgotamento de recursos | Enxurrada de imagens ou imagens pesadas | Rate limit por usuário e servidor, semáforo global de OCR, fila limitada |
| SSRF | URLs arbitrárias em anexos ou em `@bot p <url>` | Anexos só do CDN do Discord via HTTPS; allowlist de hosts para links; sem fonte HTTP genérica; sem redirecionamento não validado |
| Leitura de arquivos locais | Fonte local do Lavaplayer | Nunca registrar `LocalAudioSourceManager` |
| Injeção via texto | Texto do OCR virando comando ou URL | Tratar como dado; sem shell; o texto do OCR nunca vai para outro processo; consulta sempre com prefixo `ytsearch:`; URLs descartadas |
| Spam de menções e markdown | “@everyone” na imagem ou no título de um vídeo | `setAllowedMentions(emptyList())`, escapar com `MarkdownSanitizer`, limitar o tamanho das mensagens |
| Vazamento do token | Commit, log, dump | Variável de ambiente, `.gitignore`, nunca logar, rotacionar no Developer Portal |
| Controle indevido | Usuário de fora do canal de voz mexe no bot | Exigir o mesmo canal de voz; só o autor confirma a importação; cargo DJ opcional |
| Dependência comprometida | Pacote ou snapshot malicioso | Versões fixas, revisar atualizações, evitar snapshots, verificar CVEs (OWASP Dependency-Check, Dependabot) |
| Crash de código nativo | Tesseract ou libdave | Tesseract em processo separado; supervisão com `Restart=on-failure` |
| Privacidade | Prints com dados pessoais | Processar em memória, não persistir, logar só metadados, TTL nas sessões |

### 8.2 Controles de implementação

- **Anexos:** aceitar `image/png` e `image/jpeg`; conferir os bytes iniciais (`89 50 4E 47` e `FF D8 FF`), nunca só a extensão ou o content-type; limites iniciais de 8 MB, 4096×4096 px e 3 imagens por mensagem (configuráveis). Ler as dimensões com `ImageReader` antes de decodificar por inteiro.
- **Operação:** usuário dedicado e sem privilégios; no systemd, `NoNewPrivileges=yes`, `PrivateTmp=yes`, `ProtectSystem=strict`, `MemoryMax` e `CPUQuota` (ou container com limites). Convite com permissões mínimas: ver canais, enviar mensagens, conectar e falar, com os escopos `bot` e `applications.commands`.
- **Se o bot virar público:** política de privacidade e revisão da Developer Policy do Discord e da LGPD.

## 9. Configuração

| Variável | Padrão | Descrição |
| --- | --- | --- |
| `DISCORD_TOKEN` | — | Token do bot (obrigatória) |
| `OCR_LANGS` | `por+eng` | Idiomas do Tesseract |
| `OCR_TIMEOUT_MS` | `8000` | Timeout por execução (calibrar) |
| `OCR_MAX_CONCURRENCY` | `2` | OCRs simultâneos |
| `IMG_MAX_BYTES` | `8388608` | Tamanho máximo da imagem |
| `IMPORT_MAX_TRACKS` | `25` | Faixas por importação (cabe num select menu) |
| `IMPORT_SESSION_TTL_S` | `300` | Validade da prévia |
| `COOLDOWN_USER_S` | `15` | Intervalo entre importações |
| `QUEUE_MAX` | `200` | Tamanho máximo da fila |

Desenvolvimento: Linux x64, JDK 25 e Tesseract com os dados de idioma. Execução: `java --enable-native-access=ALL-UNNAMED -jar bot.jar` (flag a validar). A hospedagem (VPS ou container) fica para a fase final.

## 10. Observabilidade e testes

- **Logs** (SLF4J + Logback): ID de correlação por importação, duração de cada etapa e contagens; nunca o conteúdo da imagem ou do texto lido.
- **Unitários:** `TrackLineParser`, `NoiseFilter`, `Normalizer`, `AttachmentPolicy` e `RateLimiter` (com relógio injetável).
- **Golden tests do parser:** fixtures com a saída TSV do Tesseract de prints reais (anonimizados) e o resultado esperado.
- **Segurança:** arquivos corrompidos, truncados, com dimensões gigantes, content-type falso e não-imagens renomeadas devem ser rejeitados, ou estourar timeout, sem derrubar o bot.
- **Avaliação do OCR:** 20 a 30 prints com gabarito; precisão e recall por faixa após normalização; a meta inicial de recall em prints limpos fica a calibrar (algo perto de 80%).
- **Integração:** Tesseract real sobre um print de teste, `TrackResolver` com stub e o JDA testado manualmente num servidor de testes.

## 11. Roadmap

| Fase | Entregas | Critério de aceitação |
| --- | --- | --- |
| 0. Setup | App no Developer Portal, token, convite, projeto de build, `/ping` | Bot online responde `/ping` |
| 1. Música básica | Entrar e sair, tocar por link, fila, skip e stop, JDAVE | Áudio estável por mais de 10 min, sem erro de DAVE |
| 2. Busca | `/play` por nome e playlist, `@bot p ...`, desconexão por ociosidade | Os dois jeitos de comando funcionam |
| 3. OCR offline | CLI que lê uma imagem e imprime as faixas; pré-processamento; parser com fixtures; avaliação | Meta de recall atingida no conjunto de teste |
| 4. Integração | Menção + imagem, prévia com botões, menu de contexto, sessões com TTL | Fluxo completo num servidor de teste |
| 5. Endurecimento | Limites, rate limit, testes de segurança, logs, systemd ou container | Controles da seção 8 concluídos |

## 12. Riscos e decisões em aberto

| Risco | Prob. | Impacto | Mitigação |
| --- | --- | --- | --- |
| Instabilidade do YouTube (SABR, cifra, po-token) | Alta | Alto | Fonte atrás de interface, reserva no SoundCloud, atualizar o youtube-source |
| Termos de uso do YouTube | Média | Alto | Uso privado e sem monetização (Groovy e Rythm foram encerrados em 2021 após notificações do Google) |
| JDAVE jovem, Java 25, só Linux x64 | Média | Médio | Fixar versões e testar já na fase 1 |
| Qualidade do OCR variável | Alta | Médio | Confirmação, múltiplos layouts, busca tolerante, avaliação contínua |
| Escopo grande para iniciante | Média | Médio | Fases curtas; OCR offline antes da integração |

**Decisões em aberto:** (1) Maven ou Gradle; (2) OCR por processo externo (recomendado) ou Tess4J; (3) Lavaplayer embutido (recomendado) ou Lavalink; (4) fontes de áudio além do YouTube; (5) hospedagem; (6) idiomas das mensagens (pt-BR primeiro); (7) configuração por servidor e cargo DJ já na v1 ou depois.

## 13. Referências

Verificadas em 29/10/2026.

- Discord, DAVE obrigatório: https://discord.com/blog/every-voice-and-video-call-on-discord-is-now-end-to-end-encrypted
- JDA, notas de versão (DAVE a partir da 6.3.0): https://github.com/discord-jda/JDA/releases
- JDAVE: https://github.com/MinnDevelopment/jdave
- youtube-source: https://github.com/lavalink-devs/youtube-source
- Discord, intents privilegiados: https://docs.discord.com/developers/gateway/you-might-not-need-a-privileged-intent
- Tess4J: https://central.sonatype.com/artifact/net.sourceforge.tess4j/tess4j/5.18.0
- Relato da quebra do YouTube em setembro de 2026 (fonte secundária): https://github.com/worddevs/server-lavalink/releases/tag/1.0.1
