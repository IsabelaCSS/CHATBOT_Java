<div align="center">

<img src="https://img.shields.io/badge/Java-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white"/>
<img src="https://img.shields.io/badge/Telegram-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white"/>
<img src="https://img.shields.io/badge/Gmail-D14836?style=for-the-badge&logo=gmail&logoColor=white"/>
<img src="https://img.shields.io/badge/Status-Em%20Produção-brightgreen?style=for-the-badge"/>

# 🤖 Chatbot Inteligente — ABIRE
### Automação de Atendimento via Telegram com Java

*Desenvolvido para a Associação Brasileira das Indústrias Recicladoras*

</div>

---

## 📌 Sobre o Projeto

A **ABIRE** enfrentava um gargalo crítico no atendimento: demora nas respostas, acúmulo de demandas e falta de padronização. Com um time pequeno e volume crescente de solicitações de associados e parceiros, o atendimento manual estava comprometendo a experiência e a imagem da associação.

**A solução:** um chatbot automatizado, integrado ao Telegram, capaz de atender simultaneamente múltiplos usuários, coletar dados, enviar e-mails e escalar para humanos — tudo isso sem nenhuma intervenção manual para os fluxos mais comuns.

> Este projeto nasceu de uma demanda real, levantada em reunião presencial com a diretoria da ABIRE, e foi desenvolvido do zero pela equipe do Grupo 8 — FATEC, dentro da disciplina de Engenharia de Software I.

---

## ✨ Destaques Técnicos

| Funcionalidade | Detalhe |
|---|---|
| 🗂️ Gerenciamento de estado por sessão | Cada usuário tem seu próprio contexto isolado via `ConcurrentHashMap`, sem interferência entre atendimentos simultâneos |
| ✅ Validação de CPF e CNPJ | Implementação do algoritmo completo de verificação de dígitos verificadores, sem libs externas |
| 📧 E-mail automático | Disparo via Gmail SMTP com JavaMail assim que o usuário conclui um formulário |
| ⏱️ Controle de inatividade | Scheduler verifica sessões a cada minuto: aviso aos 5 min, encerramento automático aos 20 min |
| 👥 Handoff humano | Quando o bot não consegue resolver, transfere o usuário para o grupo interno de atendimento da ABIRE com todas as informações necessárias |
| 🔐 Credenciais por variáveis de ambiente | Nenhuma senha ou token no código-fonte |

---

## 🧭 Fluxo de Atendimento

```
Usuário envia /start
        │
        ▼
┌─────────────────────────────────────────┐
│            Menu Principal               │
│  1 · Lei de Incentivo à Reciclagem      │
│  2 · Criar Cooperativa / Empresa        │
│  3 · Mentoria com Felipe Andrade        │
│  4 · Tornar-se Associado da ABIRE       │
│  5 · Outras Informações                 │
│  6 · Falar com Atendente                │
└─────────────────────────────────────────┘
        │
        ├─ Opção 1 ──► Coleta e-mail → Valida → Envia e-mail automático
        │
        ├─ Opção 4 ──► Formulário completo (7 campos) → Valida CPF/CNPJ
        │               → Envia e-mail + notifica grupo interno
        │
        ├─ Opção 6 ──► Ativa modo humano → Mensagens vão ao grupo interno
        │               → Atendente responde → Encerra com comando
        │
        └─ Inatividade ► Aviso (5 min) → Encerramento automático (20 min)
```

---

## 🛠️ Tecnologias e Arquitetura

```
┌──────────────────────────────────────────────────────┐
│                   AbireChatBot.java                  │
│                                                      │
│  ┌─────────────┐   ┌──────────────┐   ┌──────────┐  │
│  │  Telegram   │   │  Formulários │   │  E-mail  │  │
│  │  Long Poll  │   │  & Validação │   │   SMTP   │  │
│  └─────────────┘   └──────────────┘   └──────────┘  │
│                                                      │
│  ┌─────────────────────────────────────────────────┐ │
│  │         Estado de Sessão (ConcurrentHashMap)    │ │
│  │  statusMap · etapaMap · formTemp · lastSeenMap  │ │
│  └─────────────────────────────────────────────────┘ │
│                                                      │
│  ┌──────────────────────────────────────────────┐    │
│  │   ScheduledExecutor — Verificação de Sessões │    │
│  └──────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────┘
```

**Stack:**
- **Java 11** — linguagem principal
- **TelegramBots API 6.x** — integração com Telegram (Long Polling)
- **JavaMail / Jakarta Mail** — envio de e-mails via SMTP
- **ScheduledExecutorService** — monitoramento de sessões inativas

---

## 📐 Decisões de Projeto

**Por que Long Polling?**
O ambiente de produção da ABIRE não possui um servidor com IP fixo e certificado SSL para receber Webhooks. Long Polling elimina essa dependência e mantém o bot operacional em qualquer infraestrutura.

**Por que estado em memória?**
O volume de atendimentos simultâneos da ABIRE é baixo e a persistência de sessões entre reinicializações não era um requisito. Mapas concorrentes oferecem latência mínima e zero dependência de banco de dados, simplificando o deploy.

**Por que validação própria de CPF/CNPJ?**
Evitar dependências desnecessárias. O algoritmo é simples, bem documentado e não justifica a inclusão de uma biblioteca externa apenas para essa finalidade.

---

## 📊 Impacto Esperado

- ⚡ Redução no tempo médio de resposta de **horas para segundos**
- 🔄 Capacidade de atender múltiplos usuários **simultaneamente**
- 📋 Padronização de **100%** das respostas nos fluxos automatizados
- 👨‍💼 Equipe humana focada apenas em demandas que **realmente precisam** de atendimento personalizado
- 📧 Registro automático de **todos os contatos** com dados estruturados

---

## 👨‍💻 Sobre o Desenvolvimento

Este projeto foi desenvolvido como parte do **Projeto de Curricularização da Extensão** da FATEC, com foco em entregar uma solução real para uma organização real.

O processo incluiu:
- Reunião presencial com a diretoria da ABIRE para levantamento de requisitos
- Elaboração de TAP (Termo de Abertura de Projeto)
- Análise de requisitos funcionais e não funcionais
- Prototipação do fluxo de atendimento
- Desenvolvimento e entrega do sistema funcional

---

## 👥 Equipe — Grupo 8 · FATEC

| Integrante | Papel |
|---|---|
| Rafael Delazeri | Gerente de Projeto |
| Alexandre Kendee Fushimi Junior | Desenvolvedor |
| Isabela de Oliveira Alves | Desenvolvedora |
| Rafael Fernandes Farias | Documentação |

**Curso:** Análise e Desenvolvimento de Sistemas  
**Disciplina:** Engenharia de Software I  
**Orientadora:** Prof.ª Paula Lacerda Rocha  
**Cliente:** ABIRE — Associação Brasileira das Indústrias Recicladoras  

---

<div align="center">

*Feito com ♻️ por Grupo 8 — FATEC · 2026*

</div>
