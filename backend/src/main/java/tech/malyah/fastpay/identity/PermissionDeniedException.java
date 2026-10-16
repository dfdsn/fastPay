package tech.malyah.fastpay.identity;

/**
 * O vínculo é válido, mas não tem a permissão da ação (H11, CA-04). Recusa no servidor mesmo por chamada direta,
 * com a tela escondendo ou não o botão.
 */
public class PermissionDeniedException extends RuntimeException {

    private final Permission permission;

    public PermissionDeniedException(Permission permission) {
        super("Sem a permissão " + permission + " na unidade.");
        this.permission = permission;
    }

    public Permission permission() {
        return permission;
    }
}
