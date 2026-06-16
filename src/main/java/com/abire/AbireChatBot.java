package com.abire;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;  
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class AbireChatBot extends TelegramLongPollingBot {

    // ==================== CONFIGURAÇÕES ====================
private static final String BOT_TOKEN    = System.getenv("BOT_TOKEN");
private static final String BOT_USERNAME = System.getenv("BOT_USERNAME");
private static final String EMAIL_REMETENTE = System.getenv("EMAIL_REMETENTE");
private static final String EMAIL_SENHA  = System.getenv("EMAIL_SENHA");
private static final long   GRUPO_ATENDIMENTO_ID = Long.parseLong(System.getenv("GRUPO_ID"));

    // ==================== ESTADO GLOBAL ====================
    private final Map<String, String>              statusMap   = new ConcurrentHashMap<>();
    private final Map<String, Long>                lastSeenMap = new ConcurrentHashMap<>();
    private final Map<String, String>              lastSeenName= new ConcurrentHashMap<>();
    private final Map<String, Boolean>             fluxoMap    = new ConcurrentHashMap<>();
    private final Map<String, Long>                avisadoMap  = new ConcurrentHashMap<>();
    private final Map<String, String>              emailTemp1  = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> formTemp4   = new ConcurrentHashMap<>();
    private final Map<String, String>              etapaMap    = new ConcurrentHashMap<>();

    private static final Set<String> CALLBACKS_VALIDOS = new HashSet<>(Arrays.asList(
        "/start","voltar_menu","1_curso","2_mentoria","ir_mentoria",
        "Atendimento","sobreMentoria","OqueAbire","FuncionaReci",
        "Projetos","Parcerias","3_geralencerramento"
    ));

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    // ==================== CONSTRUTOR ====================
    public AbireChatBot() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::verificarSessoes, 1, 1, TimeUnit.MINUTES);
    }

    @Override public String getBotUsername() { return BOT_USERNAME; }
    @Override public String getBotToken()    { return BOT_TOKEN; }

    // ==================== PONTO DE ENTRADA ====================
    @Override
    public void onUpdateReceived(Update update) {
        String chatIdStr;
        String userMessage;
        String firstName;
        boolean isGroup = false;

        if (update.hasCallbackQuery()) {
            chatIdStr   = String.valueOf(update.getCallbackQuery().getMessage().getChatId());
            userMessage = update.getCallbackQuery().getData();
            firstName   = update.getCallbackQuery().getFrom().getFirstName();
            isGroup     = false;
        } else if (update.hasMessage() && update.getMessage().hasText()) {
            chatIdStr   = String.valueOf(update.getMessage().getChatId());
            userMessage = update.getMessage().getText().trim();
            firstName   = update.getMessage().getFrom().getFirstName();
            String tipo = update.getMessage().getChat().getType();
            isGroup     = tipo != null && tipo.contains("group");
        } else {
            return;
        }

        long chatId = Long.parseLong(chatIdStr);

        if (isGroup) { processarMensagemGrupo(update); return; }

        lastSeenMap.put(chatIdStr, System.currentTimeMillis());
        lastSeenName.put(chatIdStr, firstName);

        if (userMessage.equals("/start")) {
            fluxoMap.remove(chatIdStr);
            etapaMap.remove(chatIdStr);
            formTemp4.remove(chatIdStr);
            emailTemp1.remove(chatIdStr);
            statusMap.remove("status_" + chatIdStr);
            processarMenu(chatId, chatIdStr, "/start", firstName);
            return;
        }

        boolean isHumano = "humano".equals(statusMap.get("status_" + chatIdStr));
        if (isHumano) {
            String texto = "💬 " + firstName + "\n\n🆔 " + chatIdStr +
                "\n\nMensagem:\n" + userMessage +
                "\n\nResponder:\n" + chatIdStr + " sua mensagem" +
                "\n\n🔴 Para encerrar: encerrar " + chatIdStr;
            enviarMensagem(GRUPO_ATENDIMENTO_ID, texto);
            return;
        }

        boolean ehCallback = CALLBACKS_VALIDOS.contains(userMessage);
        String etapaAtual  = etapaMap.get(chatIdStr);

        if (etapaAtual != null && !ehCallback) {
            processarEtapaFormulario(chatId, chatIdStr, userMessage, etapaAtual);
            return;
        }

        boolean emFluxo = !ehCallback && Boolean.TRUE.equals(fluxoMap.get(chatIdStr));
        if (emFluxo) {
            enviarMensagem(chatId,
                "Hmm, não entendi essa mensagem 🤔\n\n" +
                "Por favor, escolha uma das opções disponíveis.\n" +
                "Ou digite /start para o início do atendimento.");
            return;
        }

        processarMenu(chatId, chatIdStr, userMessage, firstName);
    }

    // ==================== GRUPO ====================
    private void processarMensagemGrupo(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;
        String texto = update.getMessage().getText().trim();
        if (texto.isEmpty()) return;

        String[] partes = texto.split(" ", 2);
        String chatIdDestino;
        String mensagem;

        if (partes[0].equalsIgnoreCase("encerrar")) {
            chatIdDestino = partes.length > 1 ? partes[1].trim() : "";
            mensagem = "";
        } else {
            chatIdDestino = partes[0].trim();
            mensagem = partes.length > 1 ? partes[1].trim() : "";
        }

        if (chatIdDestino.isEmpty()) return;
        long chatIdLong;
        try { chatIdLong = Long.parseLong(chatIdDestino); }
        catch (NumberFormatException e) { return; }

        if (mensagem.isEmpty()) {
            statusMap.remove("status_" + chatIdDestino);
            enviarMensagem(chatIdLong,
                "Seu atendimento foi encerrado pela nossa equipe. 😊\n\n" +
                "Caso precise de mais ajuda, envie /start para voltar ao menu.");
        } else {
            enviarMensagem(chatIdLong, mensagem);
        }
    }

    // ==================== MENU ====================
    private void processarMenu(long chatId, String chatIdStr, String userMessage, String firstName) {
        switch (userMessage) {
            case "/start": case "voltar_menu":
                fluxoMap.remove(chatIdStr);
                etapaMap.remove(chatIdStr);
                formTemp4.remove(chatIdStr);
                enviarMensagem(chatId,
                    "Olá! Sou a Vanessa, assistente virtual da ABIRE 👋\n\n" +
                    "Posso te ajudar com as opções abaixo:\n\n" +
                    "1 - Receber informações sobre a Lei de Incentivo à Reciclagem\n" +
                    "2 - Criar cooperativa ou empresa de reciclagem\n" +
                    "3 - Mentoria com Felipe Andrade\n" +
                    "4 - Se tornar associado da ABIRE\n" +
                    "5 - Outras informações da ABIRE\n" +
                    "6 - Falar com um atendente\n\n" +
                    "Digite o número da opção desejada.");
                break;
            case "1":
                fluxoMap.put(chatIdStr, true);
                etapaMap.put(chatIdStr, "aguardando_email1");
                enviarMensagem(chatId,
                    "A ABIRE ajuda empresas a acessarem a Lei de Incentivo à Reciclagem, permitindo captar recursos para projetos ambientais.\n\n" +
                    "Para continuar, por favor digite seu e-mail para acompanhamento.\n\n" +
                    "Se quiser voltar ao menu é só digitar /start.");
                break;
            case "2":
                fluxoMap.put(chatIdStr, true);
                enviarMenuCriarCooperativa(chatId);
                break;
            case "3": case "ir_mentoria":
                fluxoMap.put(chatIdStr, true);
                enviarMenuMentoriaFelipe(chatId);
                break;
            case "4":
                fluxoMap.put(chatIdStr, true);
                iniciarFormularioAssociado(chatId, chatIdStr);
                break;
            case "5":
                fluxoMap.put(chatIdStr, true);
                enviarMenuOutrasInformacoes(chatId);
                break;
            case "6": case "Atendimento":
                enviarMensagem(chatId,
                    "Perfeito! Vou encaminhar você para nossa equipe.\n\n" +
                    "⏳ Tempo médio de resposta: 2 minutos.\n\n" +
                    "Enquanto isso, envie sua dúvida detalhada.\n" +
                    "Nossa equipe responderá em breve.");
                statusMap.put("status_" + chatIdStr, "humano");
                String nome = lastSeenName.getOrDefault(chatIdStr, "Usuário");
                enviarMensagem(GRUPO_ATENDIMENTO_ID,
                    "💬 " + nome + "\n\n🆔 " + chatIdStr +
                    "\n\nMensagem:\n(Solicitou atendimento humano)" +
                    "\n\nResponder:\n" + chatIdStr + " sua mensagem" +
                    "\n\n🔴 Para encerrar: encerrar " + chatIdStr);
                break;
            case "3_geralencerramento":
                fluxoMap.remove(chatIdStr);
                etapaMap.remove(chatIdStr);
                formTemp4.remove(chatIdStr);
                lastSeenMap.remove(chatIdStr);
                statusMap.remove("status_" + chatIdStr);
                enviarMensagem(chatId,
                    "✅ Atendimento encerrado.\n" +
                    "Obrigado por entrar em contato com a ABIRE ♻️\n\n" +
                    "Sempre que precisar, envie /start para iniciar um novo atendimento.");
                break;
            case "1_curso":
                fluxoMap.put(chatIdStr, true); enviarInfoCursos(chatId); break;
            case "2_mentoria":
                fluxoMap.put(chatIdStr, true); enviarInfoMentoria(chatId); break;
            case "sobreMentoria":
                enviarSobreMentoria(chatId); break;
            case "OqueAbire":
                fluxoMap.put(chatIdStr, true); enviarOqueAbire(chatId); break;
            case "FuncionaReci":
                fluxoMap.put(chatIdStr, true); enviarFuncionaReci(chatId); break;
            case "Projetos":
                fluxoMap.put(chatIdStr, true); enviarProjetos(chatId); break;
            case "Parcerias":
                fluxoMap.put(chatIdStr, true); enviarParcerias(chatId); break;
            default:
                enviarMensagem(chatId,
                    "Hmm, não entendi essa mensagem 🤔\n\n" +
                    "Por favor, escolha uma das opções disponíveis.\n" +
                    "Ou digite /start para o início do atendimento.");
                break;
        }
    }

    // ==================== FORMULÁRIOS ====================
    private void processarEtapaFormulario(long chatId, String chatIdStr,
                                           String userMessage, String etapa) {
        System.out.println("[DEBUG] chatId=" + chatIdStr + " etapa=" + etapa + " msg=" + userMessage);

        switch (etapa) {

            // -------- OPÇÃO 1: email simples --------
            case "aguardando_email1":
                if (EMAIL_PATTERN.matcher(userMessage).matches()) {
                    emailTemp1.put(chatIdStr, userMessage);
                    etapaMap.remove(chatIdStr);
                    String nomeU = lastSeenName.getOrDefault(chatIdStr, "Usuário");
                    enviarEmail(userMessage,
                        "ABIRE - Informações sobre Lei de Incentivo à Reciclagem",
                        "Olá!\n\nRecebemos seu interesse na Lei de Incentivo à Reciclagem ♻️\n\n" +
                        "A ABIRE auxilia empresas e projetos ambientais na captação de recursos através da Lei de Incentivo à Reciclagem.\n\n" +
                        "A Lei de Incentivo permite que empresas destinem parte de seus impostos para projetos de reciclagem e sustentabilidade, gerando impacto ambiental positivo e benefícios fiscais.\n\n" +
                        "Como funciona:\n" +
                        "✅ Empresas podem destinar recursos para projetos aprovados\n" +
                        "✅ Benefícios fiscais e responsabilidade socioambiental\n" +
                        "✅ Apoio técnico da ABIRE em todo o processo\n\n" +
                        "Em breve nossa equipe entrará em contato com mais informações detalhadas sobre como sua empresa pode participar.\n\n" +
                        "Atenciosamente,\nEquipe ABIRE");
                    enviarMensagem(chatId,
                        "Agradecemos o contato!\n" +
                        "Seu e-mail foi registrado com sucesso.\n" +
                        "Em breve você receberá mais informações no e-mail informado.");
                    enviarMensagem(GRUPO_ATENDIMENTO_ID,
                        "📩 Novo contato recebido via Telegram\n\n" +
                        "👤 Nome: " + nomeU + "\n" +
                        "🆔 Telegram ID: " + chatIdStr + "\n" +
                        "📧 E-mail: " + userMessage + "\n" +
                        "📌 Interesse: Lei de Incentivo à Reciclagem\n\n" +
                        "✅ O usuário já recebeu o e-mail automático da ABIRE.\n\n" +
                        "Responder:\n" + chatIdStr + " sua mensagem\n\n" +
                        "🔴 Para encerrar:\nencerrar " + chatIdStr);
                    enviarPerguntaFinal(chatId);
                } else {
                    enviarMensagem(chatId,
                        "E-mail inválido.\n\nPor favor, digite um e-mail válido.");
                }
                break;

            // -------- OPÇÃO 4: formulário completo --------
            case "aguardando_email4":
                if (EMAIL_PATTERN.matcher(userMessage).matches()) {
                    formTemp4.computeIfAbsent(chatIdStr, k -> new HashMap<>()).put("email", userMessage);
                    etapaMap.put(chatIdStr, "aguardando_cpf4");
                    enviarMensagem(chatId, "Digite seu CPF ou CNPJ:");
                } else {
                    enviarMensagem(chatId, "E-mail inválido.\n\nPor favor, digite um e-mail válido.");
                }
                break;

            case "aguardando_cpf4":
            case "retry_cpf4":
                String docLimpo = userMessage.replaceAll("\\D", "");
                if (validarCPFouCNPJ(docLimpo)) {
                    formTemp4.computeIfAbsent(chatIdStr, k -> new HashMap<>()).put("cpf_cnpj", docLimpo);
                    etapaMap.put(chatIdStr, "aguardando_nome4");
                    enviarMensagem(chatId, "Digite seu Nome completo:");
                } else {
                    etapaMap.put(chatIdStr, "retry_cpf4");
                    enviarMensagem(chatId, "CPF ou CNPJ inválido.\n\nPor favor, envie um documento válido.");
                }
                break;

            case "aguardando_nome4":
                formTemp4.computeIfAbsent(chatIdStr, k -> new HashMap<>()).put("nome", userMessage);
                etapaMap.put(chatIdStr, "aguardando_empresa4");
                enviarMensagem(chatId, "Digite o nome da sua Empresa:");
                break;

            case "aguardando_empresa4":
                formTemp4.computeIfAbsent(chatIdStr, k -> new HashMap<>()).put("empresa", userMessage);
                etapaMap.put(chatIdStr, "aguardando_telefone4");
                enviarMensagem(chatId, "Digite seu Telefone (com DDD):");
                break;

            case "aguardando_telefone4":
                String telefoneLimpo = userMessage.replaceAll("\\D", ""); // remove tudo que não for dígito
                    if (telefoneLimpo.length() >= 10 && telefoneLimpo.length() <= 11) {
                formTemp4.computeIfAbsent(chatIdStr, k -> new HashMap<>()).put("telefone", telefoneLimpo);
                etapaMap.put(chatIdStr, "aguardando_cidade4");
                enviarMensagem(chatId, "Digite sua Cidade/Estado (ex: São Paulo/SP):");
            } else {
                enviarMensagem(chatId,
                "Telefone inválido.\n\nPor favor, envie apenas números com DDD.\nExemplo: 11987654321");
                }
            break;

            case "aguardando_cidade4":
                formTemp4.computeIfAbsent(chatIdStr, k -> new HashMap<>()).put("cidade", userMessage);
                etapaMap.put(chatIdStr, "aguardando_mensagem4");
                enviarMensagem(chatId, "Deixe uma mensagem (opcional). Se não quiser, digite: -");
                break;

            case "aguardando_mensagem4":
                Map<String, String> form = formTemp4.computeIfAbsent(chatIdStr, k -> new HashMap<>());
                form.put("mensagem", userMessage.equals("-") ? "" : userMessage);
                etapaMap.remove(chatIdStr);
                finalizarAssociado(chatId, chatIdStr, form);
                break;

            default:
                System.out.println("[WARN] Etapa desconhecida: " + etapa + " para chatId=" + chatIdStr);
                etapaMap.remove(chatIdStr);
                enviarMensagem(chatId, "Ocorreu um erro. Digite /start para recomeçar.");
                break;
        }
    }

    // ==================== OPÇÃO 4: FINALIZAR ====================
    private void iniciarFormularioAssociado(long chatId, String chatIdStr) {
        formTemp4.put(chatIdStr, new HashMap<>());
        etapaMap.put(chatIdStr, "aguardando_email4");
        enviarMensagem(chatId,
            "🌱 Que bom ter você conosco!\n\n" +
            "Para se tornar associado da ABIRE, precisamos de algumas informações para dar continuidade ao seu cadastro.\n\n" +
            "Por favor, digite seu e-mail para começar.\n" +
            "Ou digite /start para voltar ao menu.\n\n" +
            "Assim que recebermos suas informações, nossa equipe entrará em contato com os próximos passos.");
    }

    private void finalizarAssociado(long chatId, String chatIdStr, Map<String, String> form) {
        String email    = form.getOrDefault("email", "");
        String nome     = form.getOrDefault("nome", "");
        String cpf      = form.getOrDefault("cpf_cnpj", "");
        String empresa  = form.getOrDefault("empresa", "");
        String telefone = form.getOrDefault("telefone", "");
        String cidade   = form.getOrDefault("cidade", "");
        String msg      = form.getOrDefault("mensagem", "");

        String corpoEmail =
            "Olá " + nome + "!\n\n" +
            "Recebemos sua solicitação para se tornar associado(a) da ABIRE ♻️\n\n" +
            "Os dados enviados foram recebidos com sucesso e já estão em análise pela nossa equipe.\n\n" +
            "Em breve entraremos em contato para dar continuidade ao processo de associação e apresentar os próximos passos.\n\n" +
            "━━━━━━━━━━━━━━━━━━\n" +
            "📌 DADOS RECEBIDOS\n" +
            "━━━━━━━━━━━━━━━━━━\n\n" +
            "👤 Nome:\n" + nome + "\n\n" +
            "🪪 CPF/CNPJ:\n" + cpf + "\n\n" +
            "🏢 Empresa:\n" + empresa + "\n\n" +
            "📞 Telefone:\n" + telefone + "\n\n" +
            "📍 Cidade/Estado:\n" + cidade + "\n\n" +
            "💬 Mensagem:\n" + msg + "\n\n" +
            "━━━━━━━━━━━━━━━━━━\n\n" +
            "Agradecemos pelo seu interesse em fazer parte da ABIRE e contribuir com iniciativas sustentáveis e ambientais.\n\n" +
            "Atenciosamente,\nEquipe ABIRE";

        enviarEmail(email, "Recebemos sua solicitação de associação - ABIRE", corpoEmail);

        enviarMensagem(chatId,
            "Agradecemos o contato!\n" +
            "Seu cadastro foi registrado com sucesso.\n" +
            "Em breve você receberá mais informações no e-mail informado.");

        enviarMensagem(GRUPO_ATENDIMENTO_ID,
            "📩 Nova solicitação de associação recebida via Telegram\n\n" +
            "👤 Nome:\n" + nome + "\n\n" +
            "🆔 Telegram ID:\n" + chatIdStr + "\n\n" +
            "📧 E-mail:\n" + email + "\n\n" +
            "🪪 CPF/CNPJ:\n" + cpf + "\n\n" +
            "🏢 Empresa:\n" + empresa + "\n\n" +
            "📞 Telefone:\n" + telefone + "\n\n" +
            "📍 Cidade/Estado:\n" + cidade + "\n\n" +
            "💬 Mensagem:\n" + msg + "\n\n" +
            "✅ O usuário já recebeu o e-mail automático da ABIRE.\n\n" +
            "Responder:\n" + chatIdStr + " sua mensagem\n\n" +
            "🔴 Para encerrar:\nencerrar " + chatIdStr);

        formTemp4.remove(chatIdStr);
        enviarPerguntaFinal(chatId);
    }

// ==================== ENVIO DE EMAIL ====================
    private void enviarEmail(String destinatario, String assunto, String corpo) {
        new Thread(() -> enviarEmailSync(destinatario, assunto, corpo)).start();
    }

    private void enviarEmailSync(String destinatario, String assunto, String corpo) {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        Session session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(EMAIL_REMETENTE, EMAIL_SENHA);
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(EMAIL_REMETENTE));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(destinatario));
            message.setSubject(assunto);
            message.setText(corpo);
            Transport.send(message);
            System.out.println("[EMAIL ENVIADO] Para: " + destinatario);
        } catch (MessagingException e) {
            System.out.println("[ERRO EMAIL] " + e.getMessage());
        }
    }

    // ==================== MENSAGENS ====================
    private void enviarMenuCriarCooperativa(long chatId) {
        List<List<InlineKeyboardButton>> l = new ArrayList<>();
        List<InlineKeyboardButton> b = new ArrayList<>();
        b.add(criarBotao("1","1_curso"));
        b.add(criarBotao("2","2_mentoria"));
        b.add(criarBotao("3","Atendimento"));
        b.add(criarBotao("4","voltar_menu"));
        b.add(criarBotao("5","3_geralencerramento"));
        l.add(b);
        enviarMensagemComTeclado(chatId,
            "A ABIRE oferece suporte para quem deseja criar cooperativas, empresas de reciclagem e projetos sustentáveis ♻️\n\n" +
            "Escolha uma das opções abaixo:\n\n" +
            "1 - Ver cursos\n2 - Falar sobre mentoria\n3 - Falar com atendente\n" +
            "4 - Voltar ao menu\n5 - Encerrar Atendimento\n\n" +
            "Clique no número da opção desejada.", l);
    }

    private void enviarInfoCursos(long chatId) {
        enviarMensagem(chatId,
            "🚀 Conheça os Cursos da ABIRE Academy ♻️\n\n" +
            "A ABIRE Academy oferece capacitações voltadas para sustentabilidade, reciclagem, inovação ambiental e geração de renda no setor ecológico.\n\n" +
            "📚 Cursos disponíveis:\n\n" +
            "♻️ Como Começar sua Cooperativa de Reciclagem do ZERO\n\n" +
            "💰 Valor\nDe R$ 2.297,00 por R$ 249,90 — 89% de desconto, com mentoria grátis incluída.\n\n" +
            "📚 O que você vai aprender\n" +
            "Como estruturar sua cooperativa do zero, os principais materiais recicláveis para trabalhar, técnicas para tornar o negócio rentável, além de documentação, maquinários, tipos de plásticos, vidro, metal, papel, compradores, fornecedores e margem de lucro.\n\n" +
            "🎁 Bônus exclusivos inclusos\nModelo de estatuto, apostilas, contato de contabilidade, e-book dinâmico e mentoria grátis.\n\n" +
            "🎓 Instrutores\n" +
            "Beatriz — engenheira ambiental graduada pela UFPR, com mestrado em Economia Circular e MBA em Gestão de Projetos Sustentáveis pela USP.\n\n" +
            "Felipe — mais de 20 anos no setor de reciclagem, gestor de três empresas consolidadas na área.\n\n" +
            "✅ Detalhes do curso\nAulas 100% online, acesso por 1 ano e suporte exclusivo para alunos. Mais de 1.000 alunos já realizaram o curso.\n\n" +
            "🔒 Garantia\n7 dias de garantia — se não ficar satisfeito, o valor é devolvido integralmente.\n\n" +
            "📈 Potencial de faturamento estimado\n" +
            "Cooperativas pequenas faturam entre R$ 4.000 e R$ 25.000/mês; médias entre R$ 25.000 e R$ 100.000/mês; e grandes acima de R$ 100.000/mês.\n\n" +
            "✨ Todos os cursos incluem:\n✅ Certificado digital\n✅ Material complementar\n✅ Aulas online\n✅ Suporte especializado\n\n" +
            "🌍 Gostaria de saber mais sobre nossos cursos?\n\nAcesse nossa página oficial:\nhttps://abire.org.br/cursos/");
        List<List<InlineKeyboardButton>> l = new ArrayList<>();
        List<InlineKeyboardButton> b = new ArrayList<>();
        b.add(criarBotao("1","voltar_menu"));
        b.add(criarBotao("2","ir_mentoria"));
        b.add(criarBotao("3","Atendimento"));
        b.add(criarBotao("4","3_geralencerramento"));
        l.add(b);
        enviarMensagemComTeclado(chatId,
            "Deseja mais alguma coisa?\n\n1 - Voltar ao menu\n2 - Falar sobre mentoria\n" +
            "3 - Falar com atendente\n4 - Encerrar Atendimento", l);
    }

    private void enviarInfoMentoria(long chatId) {
        enviarMensagem(chatId,
            "🚀 Mentoria com Felipe Andrade\n\n" +
            "Felipe Andrade atua há mais de 10 anos ajudando cooperativas e empresas de reciclagem a crescerem de forma sustentável.\n\n" +
            "A mentoria oferece:\n" +
            "✅ Consultoria personalizada\n✅ Estratégias de crescimento\n" +
            "✅ Suporte técnico especializado\n✅ Networking no setor de reciclagem\n\n" +
            "Com a nossa equipe você pode entrar em contato para mais informações sobre a mentoria.");
        List<List<InlineKeyboardButton>> l = new ArrayList<>();
        List<InlineKeyboardButton> b = new ArrayList<>();
        b.add(criarBotao("1","voltar_menu"));
        b.add(criarBotao("2","1_curso"));
        b.add(criarBotao("3","Atendimento"));
        b.add(criarBotao("4","3_geralencerramento"));
        l.add(b);
        enviarMensagemComTeclado(chatId,
            "Deseja mais alguma coisa?\n\n1 - Voltar ao menu\n2 - Ver cursos\n" +
            "3 - Falar com atendente\n4 - Encerrar Atendimento", l);
    }

    private void enviarMenuMentoriaFelipe(long chatId) {
        List<List<InlineKeyboardButton>> l = new ArrayList<>();
        List<InlineKeyboardButton> b = new ArrayList<>();
        b.add(criarBotao("1","sobreMentoria"));
        b.add(criarBotao("2","Atendimento"));
        b.add(criarBotao("3","voltar_menu"));
        b.add(criarBotao("4","3_geralencerramento"));
        l.add(b);
        enviarMensagemComTeclado(chatId,
            "🚀 Mentoria com Felipe Andrade\n\n" +
            "Felipe Andrade atua há mais de 10 anos ajudando cooperativas e empresas de reciclagem a crescerem de forma sustentável.\n\n" +
            "A mentoria oferece:\n" +
            "✅ Consultoria personalizada\n✅ Estratégias de crescimento\n" +
            "✅ Suporte técnico especializado\n✅ Networking no setor de reciclagem\n\n" +
            "Com a nossa equipe você pode entrar em contato para mais informações sobre a mentoria.\n\n" +
            "Você deseja:\n1 - Receber informações da mentoria\n" +
            "2 - Falar diretamente com a equipe\n3 - Voltar ao menu\n4 - Encerrar Atendimento", l);
    }

    private void enviarSobreMentoria(long chatId) {
        enviarMensagem(chatId,
            "🚀 Mentoria com Felipe Andrade\n\n" +
            "Profissional com mais de 20 anos de atuação no setor de reciclagem, reconhecido pela expertise em gestão de resíduos e pelo desenvolvimento de soluções inovadoras e sustentáveis. Empresário de visão, gerencia atualmente três empresas consolidadas de reciclagem, conduzindo equipes e projetos que promovem eficiência operacional, impacto socioambiental positivo e geração de valor para o mercado.\n\n" +
            "Destaca-se pela capacidade de enfrentar desafios do segmento, adaptando-se a diferentes cenários e impulsionando melhorias contínuas nos processos. Ao longo da carreira, liderou parcerias estratégicas com cooperativas e entidades do ramo, ampliando redes de fornecedores e promovendo a inclusão social de trabalhadores autônomos.\n\n" +
            "Demonstrando resiliência e comprometimento com a sustentabilidade, Felipe é referência em práticas responsáveis de gestão e empreendedorismo ambiental. Seu histórico inclui resultados expressivos na expansão de negócios, implementação de novas tecnologias, qualificação de equipes e engajamento da comunidade na economia circular.\n\n" +
            "Você pode entrar em contato com a nossa equipe para mais informações sobre a mentoria.");
        enviarPerguntaFinal(chatId);
    }

    private void enviarMenuOutrasInformacoes(long chatId) {
        List<List<InlineKeyboardButton>> l = new ArrayList<>();
        List<InlineKeyboardButton> b = new ArrayList<>();
        b.add(criarBotao("1","OqueAbire"));
        b.add(criarBotao("2","FuncionaReci"));
        b.add(criarBotao("3","Projetos"));
        b.add(criarBotao("4","Parcerias"));
        b.add(criarBotao("5","Atendimento"));
        b.add(criarBotao("6","voltar_menu"));
        b.add(criarBotao("7","3_geralencerramento"));
        l.add(b);
        enviarMensagemComTeclado(chatId,
            "Escolha uma opção:\n\n1 - O que é a ABIRE\n2 - Como funciona reciclagem incentivada\n" +
            "3 - Projetos da ABIRE\n4 - Parcerias\n5 - Falar com atendente\n" +
            "6 - Voltar ao menu\n7 - Encerrar Atendimento", l);
    }

    private void enviarOqueAbire(long chatId) {
        enviarMensagem(chatId,
            "O que é a ABIRE 🌱\n\n" +
            "A ABIRE é uma associação voltada para iniciativas ambientais, sustentabilidade e fortalecimento da reciclagem no Brasil. Atuamos conectando empresas, cooperativas, projetos sociais e parceiros que desejam contribuir para um futuro mais sustentável.\n\n" +
            "Nossa missão é incentivar práticas responsáveis, promover educação ambiental e desenvolver projetos que gerem impacto positivo para a sociedade e o meio ambiente.");
        enviarPosInfo(chatId, "FuncionaReci","Projetos","Parcerias",
            "2 - Como funciona reciclagem incentivada\n3 - Projetos da ABIRE\n4 - Parcerias");
    }

    private void enviarFuncionaReci(long chatId) {
        enviarMensagem(chatId,
            "Como funciona a reciclagem incentivada ♻️\n\n" +
            "A reciclagem incentivada funciona por meio de ações e programas que estimulam empresas e pessoas a participarem de iniciativas sustentáveis. Através desses projetos, materiais recicláveis podem ser destinados corretamente, reduzindo impactos ambientais e fortalecendo a economia circular.\n\n" +
            "Além disso, empresas parceiras podem apoiar projetos ambientais, gerar impacto social positivo e fortalecer suas ações de responsabilidade socioambiental.");
        enviarPosInfo(chatId, "OqueAbire","Projetos","Parcerias",
            "2 - O que é a ABIRE\n3 - Projetos da ABIRE\n4 - Parcerias");
    }

    private void enviarProjetos(long chatId) {
        enviarMensagem(chatId,
            "Projetos da ABIRE 🌍\n\n" +
            "A ABIRE desenvolve projetos voltados para sustentabilidade, educação ambiental e incentivo à reciclagem. Nossas iniciativas buscam gerar impacto ambiental positivo e apoiar comunidades, cooperativas e empresas comprometidas com o meio ambiente.\n\n" +
            "Os projetos incluem campanhas educativas, ações de coleta seletiva, programas de conscientização e iniciativas sustentáveis em parceria com organizações públicas e privadas.");
        enviarPosInfo(chatId, "OqueAbire","FuncionaReci","Parcerias",
            "2 - O que é a ABIRE\n3 - Como funciona reciclagem incentivada\n4 - Parcerias");
    }

    private void enviarParcerias(long chatId) {
        enviarMensagem(chatId,
            "Parcerias 🤝\n\n" +
            "Acreditamos que grandes resultados acontecem através da colaboração. Por isso, a ABIRE busca empresas, instituições e organizações interessadas em apoiar iniciativas ambientais e projetos sustentáveis.\n\n" +
            "As parcerias podem envolver apoio institucional, projetos sociais, ações ambientais, reciclagem incentivada, eventos e campanhas de conscientização.");
        enviarPosInfo(chatId, "OqueAbire","FuncionaReci","Projetos",
            "2 - O que é a ABIRE\n3 - Como funciona reciclagem incentivada\n4 - Projetos da ABIRE");
    }

    private void enviarPosInfo(long chatId, String cb2, String cb3, String cb4, String opcoes) {
        List<List<InlineKeyboardButton>> l = new ArrayList<>();
        List<InlineKeyboardButton> b = new ArrayList<>();
        b.add(criarBotao("1","voltar_menu"));
        b.add(criarBotao("2",cb2));
        b.add(criarBotao("3",cb3));
        b.add(criarBotao("4",cb4));
        b.add(criarBotao("5","Atendimento"));
        b.add(criarBotao("6","3_geralencerramento"));
        l.add(b);
        enviarMensagemComTeclado(chatId,
            "Deseja mais alguma coisa?\n\n1 - Voltar ao menu\n" + opcoes +
            "\n5 - Falar com atendente\n6 - Encerrar Atendimento", l);
    }

    private void enviarPerguntaFinal(long chatId) {
        List<List<InlineKeyboardButton>> l = new ArrayList<>();
        List<InlineKeyboardButton> b = new ArrayList<>();
        b.add(criarBotao("1","voltar_menu"));
        b.add(criarBotao("2","Atendimento"));
        b.add(criarBotao("3","3_geralencerramento"));
        l.add(b);
        enviarMensagemComTeclado(chatId,
            "Deseja mais alguma coisa?\n\n1 - Voltar ao menu.\n2 - Falar com atendente.\n3 - Encerrar atendimento.", l);
    }

    // ==================== SESSÕES INATIVAS ====================
    private void verificarSessoes() {
        long AVISO = 5 * 60 * 1000L, ENCERRA = 20 * 60 * 1000L;
        long agora = System.currentTimeMillis();
        for (String chatIdStr : new HashSet<>(lastSeenMap.keySet())) {
            long ts = lastSeenMap.getOrDefault(chatIdStr, 0L);
            Long avisado = avisadoMap.get(chatIdStr);
            long chatId = Long.parseLong(chatIdStr);
            if (avisado != null) {
                if ((agora - avisado) >= ENCERRA) {
                    avisadoMap.remove(chatIdStr);
                    lastSeenMap.remove(chatIdStr);
                    fluxoMap.remove(chatIdStr);
                    etapaMap.remove(chatIdStr);
                    formTemp4.remove(chatIdStr);
                    statusMap.remove("status_" + chatIdStr);
                    enviarMensagem(chatId,
                        "Olá! 😊\n\nComo você não respondeu, vamos encerrar o atendimento por aqui!\n\n" +
                        "Se preferir iniciar um novo atendimento, digite /start para voltar ao menu principal. ♻️");
                }
            } else if ((agora - ts) >= AVISO) {
                avisadoMap.put(chatIdStr, agora);
                List<List<InlineKeyboardButton>> l = new ArrayList<>();
                List<InlineKeyboardButton> b = new ArrayList<>();
                b.add(criarBotao("Encerrar Atendimento","3_geralencerramento"));
                l.add(b);
                enviarMensagemComTeclado(chatId,
                    "Olá! 😊\n\n" +
                    "Notei que você ficou um tempinho sem resposta.\n\n" +
                    "Se seu atendimento ainda estiver ativo, é só continuar a conversa enviando novas mensagens relacionadas ao assunto anterior que seguiremos de onde paramos. 💬\n\n" +
                    "As mensagens anteriores continuam disponíveis no chat para facilitar a continuidade do atendimento. 😉\n\n" +
                    "Ou, se preferir iniciar um novo atendimento, digite /start para voltar ao menu principal.\n" +
                    "Ou clique em encerrar atendimento ♻️", l);
            }
        }
    }

    // ==================== VALIDAÇÕES ====================
    private boolean validarCPFouCNPJ(String doc) {
        if (doc.length() == 11) return validarCPF(doc);
        if (doc.length() == 14) return validarCNPJ(doc);
        return false;
    }

    private boolean validarCPF(String cpf) {
        if (cpf.matches("(\\d)\\1+")) return false;
        int soma = 0;
        for (int i = 0; i < 9; i++) soma += Character.getNumericValue(cpf.charAt(i)) * (10 - i);
        int resto = (soma * 10) % 11; if (resto >= 10) resto = 0;
        if (resto != Character.getNumericValue(cpf.charAt(9))) return false;
        soma = 0;
        for (int i = 0; i < 10; i++) soma += Character.getNumericValue(cpf.charAt(i)) * (11 - i);
        resto = (soma * 10) % 11; if (resto >= 10) resto = 0;
        return resto == Character.getNumericValue(cpf.charAt(10));
    }

    private boolean validarCNPJ(String cnpj) {
        if (cnpj.matches("(\\d)\\1+")) return false;
        int tam = cnpj.length() - 2;
        String num = cnpj.substring(0, tam), dig = cnpj.substring(tam);
        int soma = 0, pos = tam - 7;
        for (int i = tam; i >= 1; i--) {
            soma += Character.getNumericValue(num.charAt(tam - i)) * pos--;
            if (pos < 2) pos = 9;
        }
        int res = soma % 11 < 2 ? 0 : 11 - (soma % 11);
        if (res != Character.getNumericValue(dig.charAt(0))) return false;
        tam++; num = cnpj.substring(0, tam); soma = 0; pos = tam - 7;
        for (int i = tam; i >= 1; i--) {
            soma += Character.getNumericValue(num.charAt(tam - i)) * pos--;
            if (pos < 2) pos = 9;
        }
        res = soma % 11 < 2 ? 0 : 11 - (soma % 11);
        return res == Character.getNumericValue(dig.charAt(1));
    }

    // ==================== HELPERS ====================
    private void enviarMensagem(long chatId, String texto) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(texto);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void enviarMensagemComTeclado(long chatId, String texto,
                                           List<List<InlineKeyboardButton>> linhas) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(texto);
        InlineKeyboardMarkup t = new InlineKeyboardMarkup();
        t.setKeyboard(linhas);
        msg.setReplyMarkup(t);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private InlineKeyboardButton criarBotao(String texto, String callback) {
        InlineKeyboardButton b = new InlineKeyboardButton();
        b.setText(texto); b.setCallbackData(callback); return b;
    }

    // ==================== MAIN ====================
    public static void main(String[] args) {
        try {
            TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
            api.registerBot(new AbireChatBot());
            System.out.println("Bot ABIRE iniciado com sucesso!");
        } catch (TelegramApiException e) { e.printStackTrace(); }
    }
}