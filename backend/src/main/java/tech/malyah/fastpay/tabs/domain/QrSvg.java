package tech.malyah.fastpay.tabs.domain;

import io.nayuki.qrcodegen.QrCode;

/**
 * Desenha o QR do link de vínculo como SVG (H15, FR-08). A matriz vem da biblioteca qrcodegen; o SVG é montado
 * aqui só com retângulos, sem texto do usuário, então a tela pode exibi-lo como imagem sem risco de script.
 */
public final class QrSvg {

    private static final int BORDER = 4;

    private QrSvg() {
    }

    public static String of(String text) {
        QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int size = qr.size + BORDER * 2;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    path.append('M').append(x + BORDER).append(',').append(y + BORDER).append("h1v1h-1z");
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + size + " " + size
                + "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>"
                + "<path d=\"" + path + "\" fill=\"#000000\"/></svg>";
    }
}
