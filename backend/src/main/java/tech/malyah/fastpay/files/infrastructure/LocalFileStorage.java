package tech.malyah.fastpay.files.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

import tech.malyah.fastpay.files.application.StoragePort;

/**
 * Storage em disco local (desenvolvimento e homologação). Cada objeto é um arquivo em
 * {@code <pasta>/<unidade>/<id>}; a chave é conferida contra um padrão fixo, então não há caminho vindo de fora.
 * A gravação vai para um arquivo temporário e depois é movida, para nunca deixar um arquivo pela metade.
 */
public class LocalFileStorage implements StoragePort {

    private static final Pattern KEY = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");

    private final Path root;

    public LocalFileStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".envio-", ".tmp");
            try {
                Files.write(temporary, content);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException notSupported) {
                    Files.move(temporary, target);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (FileAlreadyExistsException exists) {
            throw new IllegalStateException("Chave de arquivo já usada: " + key, exists);
        } catch (IOException failure) {
            throw new UncheckedIOException("Não foi possível gravar o arquivo " + key, failure);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(Files.readAllBytes(resolve(key)));
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        } catch (IOException failure) {
            throw new UncheckedIOException("Não foi possível ler o arquivo " + key, failure);
        }
    }

    private Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Chave de arquivo fora do padrão");
        }
        return root.resolve(key);
    }
}
