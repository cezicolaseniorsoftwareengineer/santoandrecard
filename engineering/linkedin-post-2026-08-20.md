# LinkedIn — Banco Santo André Card Platform

Three assets below: the post (pt-BR), the full technology list to paste under it
or into the repository README, and an English variant for international
recruiters. All figures are measured from the repository; nothing is rounded up.

---

## A. Post principal (pt-BR)

> **Construí sozinho uma plataforma de emissão de cartões com ledger de partidas dobradas. O que aprendi não foi sobre stack — foi sobre o que quebra sistemas de dinheiro.**
>
> Saldo não é coluna que o código lembra de atualizar. É soma sobre lançamentos imutáveis. E uma transação desbalanceada não é problema de persistência: ela não é uma transação.
>
> Por isso a invariante mora no construtor do `record`, antes de qualquer escrita:
>
> ```java
> BigDecimal debits  = sumOf(postings, DEBIT);
> BigDecimal credits = sumOf(postings, CREDIT);
> if (debits.compareTo(credits) != 0) {
>     throw new UnbalancedTransactionException(debits, credits);
> }
> ```
>
> Não existe caminho de código que escreva lançamentos sem construir esse record primeiro. A regra não é uma convenção que alguém pode esquecer — é inalcançável por fora.
>
> **As sete coisas que realmente aprendi construindo isso:**
>
> **1. H2 mente sobre lock.** O teste de concorrência do ledger roda contra PostgreSQL real, porque a ideia que o H2 tem de um lock pessimista de escrita não é a do PostgreSQL. Duas compras simultâneas no mesmo cartão não podem gastar o mesmo dinheiro duas vezes — e isso só se prova no banco que vai para produção.
>
> **2. Idempotência é garantia de banco, não consulta em cache.** Índice único em `(tenant_id, idempotency_key)`. Quem perde a corrida lê o resultado de quem ganhou, em vez de tentar de novo. Um pagamento repetido é cobrado uma vez.
>
> **3. Evento de dinheiro que falhou é problema de operador, não lixo.** Outbox transacional: gravado na mesma transação do dinheiro, drenado fora do request path, publica-então-marca. Depois do limite de tentativas o evento continua visível — nunca descartado.
>
> **4. Redis fora do readiness, de propósito.** O throttle e o cache degradam abertos. Deixar o Redis reprovar a probe faria o Kubernetes tirar de serviço um pod que ainda consegue receber pagamentos. Indisponibilidade de cache é lentidão, não doença.
>
> **5. Rede lenta de adquirente recusa, não debita.** Timeout, circuit breaker, fallback fail-closed, HTTP 503. A assimetria importa: em dinheiro, o erro seguro é recusar.
>
> **6. Carga é descartada, não enfileirada dentro de transação.** Admissão limitada, HTTP 429 com `Retry-After`. Fila dentro de transação aberta é como um serviço de pagamento morre segurando locks.
>
> **7. Um serviço pode responder tudo em 10ms recusando tudo — e as métricas de JVM ficam perfeitas.** Por isso os dashboards medem dinheiro movimentado, recusas por motivo, holds contra capturas, entrega do outbox e p99 dos endpoints que carregam dinheiro.
>
> **O que a CI faz em cada push (8 jobs):** sobe a aplicação empacotada contra PostgreSQL real para o Flyway aplicar as 12 migrations e o Hibernate validar o mapping; roda a jornada do cliente em browser contra um Keycloak real; renderiza os manifests do Kubernetes, valida em modo strict e pergunta ao registry se cada tag de imagem referenciada existe de fato — esse job existe porque uma tag escrita à mão divergiu em quatro lugares. Mais CodeQL nas duas linguagens, OSV-Scanner sobre a árvore resolvida de dependências, e pisos de cobertura que reprovam o build.
>
> Os pisos são 82% de instruções e 57% de branches, medidos do que a suíte alcança hoje. Sobem quando a suíte merece. Nunca descem para um build passar — piso que se move para encontrar o código não é piso.
>
> **Também sei o que ainda falta**, e está escrito no repositório: sem CSP e HSTS, sem RLS no PostgreSQL como defesa em profundidade, sem SBOM e assinatura de imagem no release, e sem Angular Router. Nenhum é estrutural. Um projeto sem lista de pendências não é um projeto maduro — é um projeto que ninguém revisou.
>
> Banco Santo André é uma instituição fictícia. O código é original, não carrega marca de terceiros, e nunca deve receber dado real de cliente.
>
> Repositório nos comentários. Crítica técnica é bem-vinda — de preferência a que dói.
>
> #Java #Quarkus #Angular #PostgreSQL #Kubernetes #Kafka #Payments #Fintech #SoftwareEngineering #DistributedSystems

---

## B. Lista completa de tecnologias

Cole como primeiro comentário do post, ou como seção do README. Cada item está
presente no repositório.

**Linguagens e runtime**
Java 17 LTS · TypeScript 5.9 · SQL (PostgreSQL) · HTML · CSS · Bash · PowerShell

**Backend — framework e plataforma**
Quarkus 3.33 · Jakarta EE: CDI/ArC, JAX-RS (RESTEasy Reactive), JPA, Bean
Validation (Hibernate Validator), JTA · Quarkus REST Jackson · Quarkus Scheduler ·
SmallRye Health · SmallRye OpenAPI + Swagger UI · SmallRye Fault Tolerance
(timeout, circuit breaker, fallback, bulkhead) · MicroProfile Config

**Dados e persistência**
PostgreSQL 17 · Hibernate ORM com Panache · `schema-management.strategy=validate`
(o código nunca reformata o banco) · Flyway, 12 migrations forward-only ·
`NUMERIC(19,2)` com `BigDecimal` e escala validada no construtor · locks
pessimistas (`PESSIMISTIC_WRITE`) como mutex por cliente · locking otimista com
`@Version` · H2 em modo de compatibilidade PostgreSQL para a suíte rápida ·
Redis 8 (Quarkus Redis Client) para throttle e cache de projeção

**Mensageria e integração**
Apache Kafka 4.1 (Quarkus Kafka Client) · padrão Transactional Outbox com
publish-then-mark, `event-id` estável e semântica at-least-once · relay agendado
fora do request path

**Domínio financeiro**
Ledger de partidas dobradas com plano de contas · saldo derivado de lançamentos
imutáveis · reconciliação projeção-contra-livro · idempotência com índice único ·
autorização de cartão com hold, captura, reversão e expiração · ciclo de faturas,
fechamento, pagamento e inadimplência · política de juros versionada com teto de
taxa · parcelamento · cartão pré-pago e crédito · multi-tenancy com escopo de
tenant em toda consulta

**Segurança**
Keycloak 26.4, realm como código · OpenID Connect Authorization Code Flow com
PKCE implementado à mão, sem biblioteca de adapter · OAuth 2.0 Bearer Token ·
Quarkus OIDC como resource server, roles a partir do access token · autorização
deny-by-default em `/api/*` · RBAC por role · identidade sempre derivada de claim
verificada, nunca de header de request · PBKDF2WithHmacSHA256, 210.000 iterações,
salt de 16 bytes por cartão, chave de 256 bits · comparação em tempo constante
(`MessageDigest.isEqual`) · orçamento durável de tentativas de PIN · CORS com
allow-list, nunca wildcard · access token só em memória, refresh token em
`sessionStorage` · validação de Luhn com BIN fictício que nenhuma rede roteia

**Front end**
Angular 21 · standalone components · Signals e `computed` como único mecanismo de
estado · `ChangeDetectionStrategy.OnPush` em todos os componentes · functional
`HttpInterceptorFn` para bearer token e chave de idempotência · diretiva
customizada de moeda BRL · CSS puro, sem framework de UI · configuração em runtime
por `config.json` montado de ConfigMap

**Testes**
JUnit 5 · Quarkus Test · RestAssured para a superfície HTTP · Testcontainers
PostgreSQL · `@TestSecurity` e `quarkus-test-security-oidc` para identidade nos
testes · testes de concorrência com múltiplas threads contra PostgreSQL real ·
JaCoCo com pisos que reprovam o build (instruções, branches e um piso próprio para
o domínio) · benchmarks isolados por JUnit tag · Vitest + jsdom no front ·
Playwright para a jornada em browser contra Keycloak real

**Observabilidade**
OpenTelemetry 1.62 (traces, exporter OTLP, sampler configurável, supressão de URIs
não-aplicacionais) · Micrometer com registry Prometheus · métricas de negócio
(dinheiro movimentado, recusas por motivo, holds contra capturas, entrega do
outbox) · Grafana 12 com dashboard provisionado · Tempo 2.9 para traces ·
Prometheus 3.6 · trace id e span id em toda linha de log

**Infraestrutura e entrega**
Docker · Docker Compose com profile de observabilidade · multi-stage builds ·
imagem nginx-unprivileged escutando em 8080 · Kubernetes: Deployment,
StatefulSet, Service, Ingress, ConfigMap, Secret, PodDisruptionBudget, topology
spread constraints, readiness e liveness probes, requests e limits em todo
container, `runAsNonRoot`, `drop: ["ALL"]`, `readOnlyRootFilesystem`,
compatibilidade com o Pod Security Standard restricted · Kustomize · GitHub
Actions com 8 jobs · Docker Buildx · GHCR · `docker/metadata-action` para
tagging · CodeQL (`security-and-quality`) nas duas linguagens · OSV-Scanner sobre
a árvore resolvida de dependências · `npm audit` · kubeconform em modo strict ·
job que resolve cada referência de imagem contra o registry · retenção de
registry automatizada

**Práticas de engenharia**
Arquitetura hexagonal com ports e adapters · Domain-Driven Design (agregados,
value objects, invariantes no domínio) · `domain/` com zero dependências para
fora, verificado · CQRS leve na leitura de projeções · ADRs · runbooks de bring-up
e rotação de credencial · log de rotação de credenciais · evidence ledger
hash-chained · migrations forward-only · trunk-based com gates de CI · comentários
que registram decisão e incidente, não sintaxe

---

## C. English variant (shorter, for international recruiters)

> **I built a card issuing and ledger platform on my own. What I learned wasn't the stack — it was what actually breaks money systems.**
>
> A balance is not a column the code remembers to update. It is a sum over immutable postings. And an unbalanced transaction is not a persistence problem — it is not a transaction at all, so the invariant lives in the record constructor, before anything is written. No code path can write postings without constructing it first.
>
> Seven things I learned building it:
>
> 1. **H2 lies about locks.** The ledger concurrency test runs against real PostgreSQL, because H2's idea of a pessimistic write lock is not PostgreSQL's. Two concurrent purchases on one card cannot spend the same money twice — and that is only provable on the database that ships.
> 2. **Idempotency is a database guarantee**, not a cache lookup. Unique index on `(tenant_id, idempotency_key)`; the loser of the race reads the winner's result.
> 3. **A failed money event is an operator's problem, not garbage.** Transactional outbox, publish-then-mark, and after the attempt limit the event stays visible rather than being dropped.
> 4. **Redis is deliberately excluded from readiness.** Both features degrade open; failing the probe would have Kubernetes pull a pod that can still take payments.
> 5. **A slow acquirer network refuses rather than debits.** Timeout, circuit breaker, fail-closed fallback, HTTP 503. In money, the safe error is "no".
> 6. **Load sheds instead of queueing inside a transaction** — bounded admission, HTTP 429 with `Retry-After`.
> 7. **A service can answer everything in 10ms while declining everything, and the JVM gauges will look perfect.** So the dashboards measure money moved, refusals by reason, holds against captures, outbox delivery, and p99 on the endpoints that carry money.
>
> Eight CI jobs per push, including the packaged app booting against real PostgreSQL so Flyway applies all 12 migrations and Hibernate validates the mapping, a browser journey against real Keycloak, and a job that asks the registry whether every image tag the manifests reference actually exists — that one exists because a hand-written tag drifted in four places. Coverage floors fail the build; they rise when the suite earns it and never drop to make a build pass.
>
> I also know what is missing, and it is written in the repository: no CSP or HSTS, no PostgreSQL row-level security as defence in depth, no SBOM or image signature at release, no Angular Router. None of it structural.
>
> Stack: Java 17, Quarkus, PostgreSQL 17, Redis, Kafka, Keycloak (OIDC + PKCE), Angular 21 with signals, OpenTelemetry, Prometheus, Grafana, Tempo, Docker, Kubernetes, Kustomize, GitHub Actions, CodeQL, OSV-Scanner, Testcontainers, Playwright.
>
> Banco Santo André is a fictitious institution. The code is original, carries no third-party branding, and must never receive real customer data.
>
> Technical criticism welcome — preferably the kind that stings.

---

## Notas de uso

- O LinkedIn não renderiza bloco de código. No post principal, ou converta o
  trecho Java em imagem (carrossel funciona bem), ou reduza a duas linhas em
  texto corrido.
- Post longo compete com o limite de "ver mais": as três primeiras linhas
  decidem tudo. Elas já carregam a tese.
- A seção B como primeiro comentário rende mais alcance do que dentro do post, e
  é o que engenheiro recrutador vai escanear.
- Link do repositório no primeiro comentário, não no corpo — o LinkedIn penaliza
  link externo no post.
- A seção sobre o que falta não é humildade performática: é o parágrafo que
  distingue quem revisou o próprio trabalho de quem só o publicou.

---

## E. Post revisado (2026-08-20) — 2.999 caracteres, cabe no limite de 3.000

Contagem do post anterior: **2.815 caracteres** (2.916 bytes UTF-8, 407 palavras,
41 linhas). O texto abaixo tem **2.999**, medido, e substitui aquele.

Correcoes de fato aplicadas em relacao ao anterior:
- "124 testes" estava desatualizado. A contagem atual e **244**: 201 metodos
  `@Test`/`@ParameterizedTest` no backend, 40 asserts de unidade no front e 3
  testes Playwright.
- "Zero double/float" foi removido como slogan absoluto: existe um `double` no
  projeto, no gauge do Micrometer, porque a API de metrica exige. Nenhuma quantia
  trafega nele. Afirmacao absoluta que um `grep` derruba custa mais do que ganha.
- `seccomp` e PDB conferidos nos manifests; `41 CHECK constraints` contados nas
  migrations; "zero imports para fora" no pacote `domain/` medido, nao estimado.

```text
PLATAFORMA E ECOSSISTEMA DE CARTÕES DE CRÉDITO WHITE LABEL

Em sistemas financeiros, tecnologia não entra por moda. Cada componente precisa proteger consistência, idempotência, auditabilidade ou segurança.

DOMÍNIO E DADOS
Java 17 — records imutáveis e BigDecimal em toda quantia.
Quarkus 3.33.3 — hexagonal: o pacote de domínio tem zero imports para fora.
PostgreSQL 17 — fonte única da verdade. Índice único para idempotência, 41 CHECK constraints, lock pessimista serializando débito na mesma carteira.
Ledger de partida dobrada — débito = crédito é invariante no construtor do record, antes de qualquer escrita. Saldo é soma sobre lançamentos imutáveis, não coluna; a reconciliação recomputa do livro e denuncia divergência.

MENSAGERIA E CACHE
Kafka 4.1.1 — transactional outbox: o evento é gravado na mesma transação do dinheiro e drenado fora do caminho síncrono. Esgotadas as tentativas ele continua visível: evento de dinheiro que falhou é problema de operador, não lixo.
Redis 8 — throttle de PIN e cache, fora do readiness de propósito: reprovar a probe tiraria de serviço um pod que ainda recebe pagamentos.

IDENTIDADE E RESILIÊNCIA
Keycloak 26.4 + OIDC — deny by default. Tenant e cliente saem de claims verificadas, nunca do request.
PBKDF2-HMAC-SHA256, 210 mil iterações, salt por cartão, comparação em tempo constante e limite durável de tentativas.
SmallRye Fault Tolerance — circuit breaker fail-closed (503) e admissão limitada (429): rede lenta recusa em vez de debitar, carga é descartada em vez de enfileirar em transação.

INTERFACE E OBSERVABILIDADE
Angular 21 + TypeScript 5.9 — signals, OnPush, interceptors de token e idempotência. PKCE escrito à mão e token só em memória.
OpenTelemetry, Prometheus, Tempo e Grafana, com trace id em cada log. As métricas medem dinheiro movimentado, recusas por motivo e entrega do outbox — um serviço pode responder tudo em 10ms recusando tudo, e os gauges de JVM ficariam perfeitos.

PLATAFORMA E ENTREGA
Kubernetes endurecido (non-root, FS read-only, drop ALL, seccomp, PDB), Kustomize, nginx unprivileged, GHCR via Buildx.
8 jobs de CI: a aplicação empacotada sobe contra PostgreSQL real, o Flyway aplica as migrations e o Hibernate valida o mapping; a jornada do cliente roda em browser contra Keycloak real. Mais CodeQL, OSV-Scanner e pisos de cobertura que reprovam o build.
244 testes — JUnit 5, RestAssured, Testcontainers, concorrência multi-thread em PostgreSQL, Vitest, Playwright.

E a decisão mais importante: dizer o que o sistema NÃO faz.
A instituição é educacional, fictícia. Não processa PAN, não implementa autorização de rede de cartões, não cobre todo o ciclo do emissor. Falta CSP e HSTS, row-level security e SBOM assinado — tudo listado no repositório.

Arquitetura financeira madura não é parecer perfeita. É saber onde estão os invariantes, onde estão os riscos e qual risco eliminar primeiro.

GitHub> https://lnkd.in/dppDmCMq

#Java #Quarkus #PostgreSQL #Kafka #Redis #Kubernetes #Fintech #DistributedSystems
```

### Primeiro comentario — o que nao caiu nos 3.000

> Detalhe que nao cabe no post, para quem quiser o inventario completo:
>
> Persistencia — Hibernate ORM com Panache em `schema-management=validate` (divergiu do banco, nao sobe), Flyway com 12 migrations forward-only, `NUMERIC(19,2)`, `@Version` para locking otimista, `PESSIMISTIC_WRITE` como mutex por cliente, H2 em modo PostgreSQL apenas na suite rapida.
>
> Dominio — fechamento de fatura, pagamento e inadimplencia; hold, captura, reversao e expiracao de autorizacao; politica de juros versionada com teto de taxa; parcelamento; cartao pre-pago e credito; multi-tenancy com escopo de tenant em toda consulta.
>
> Plataforma — Jakarta EE (CDI, JAX-RS, JPA, Bean Validation, JTA), Quarkus Scheduler, SmallRye Health, SmallRye OpenAPI com Swagger UI, MicroProfile Config, Docker Compose com profile de observabilidade, Kustomize, `docker/metadata-action` para tagging, kubeconform em modo strict, retencao de registry automatizada, ADRs, runbooks de bring-up e rotacao de credencial, evidence ledger hash-chained.
>
> Um detalhe do qual me orgulho mais do que da stack: existe um job de CI que pergunta ao registry se cada tag de imagem citada nos manifests realmente existe. Ele existe porque uma tag escrita a mao divergiu em quatro lugares, e o unico sintoma foi um pod que nunca subiu.
