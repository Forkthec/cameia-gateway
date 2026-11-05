package tech.cameia.gateway.config;

import com.google.firebase.ErrorCode;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Comprueba qué hace el Gateway al arrancar según lo que Firebase responda a la lectura de un usuario inexistente:
 * confirma el permiso, falla el arranque si falta o solo avisa ante una caída pasajera.
 *
 * <p>No levanta Spring: simula {@link FirebaseAuth} porque lo que se verifica es la decisión, no el SDK.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class FirebaseRevocationPermissionCheckTest {

    private final FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
    private final FirebaseRevocationPermissionCheck check = new FirebaseRevocationPermissionCheck(firebaseAuth);

    @Test
    void check_passes_whenProbeUserNotFound(CapturedOutput output) throws Exception {
        when(firebaseAuth.getUser(FirebaseRevocationPermissionCheck.PROBE_UID)).thenThrow(
                new FirebaseAuthException(ErrorCode.NOT_FOUND, "sin usuario", null, null, AuthErrorCode.USER_NOT_FOUND));

        assertThatCode(check::check).doesNotThrowAnyException();

        assertThat(output.getOut()).contains("Permiso de lectura de usuarios de Firebase confirmado");
    }

    @Test
    void check_passes_whenProbeUserExists() throws Exception {
        when(firebaseAuth.getUser(FirebaseRevocationPermissionCheck.PROBE_UID)).thenReturn(mock(UserRecord.class));

        assertThatCode(check::check).doesNotThrowAnyException();
    }

    @Test
    void check_shouldFailStartup_whenPermissionDenied() throws Exception {
        when(firebaseAuth.getUser(FirebaseRevocationPermissionCheck.PROBE_UID)).thenThrow(
                new FirebaseAuthException(ErrorCode.PERMISSION_DENIED, "sin permiso", null, null, null));

        assertThatThrownBy(check::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("roles/firebaseauth.viewer");
    }

    @Test
    void check_shouldFailStartup_whenUnauthenticated() throws Exception {
        when(firebaseAuth.getUser(FirebaseRevocationPermissionCheck.PROBE_UID)).thenThrow(
                new FirebaseAuthException(ErrorCode.UNAUTHENTICATED, "sin credencial", null, null, null));

        assertThatThrownBy(check::check).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void check_shouldFailStartup_whenProjectNotFound() throws Exception {
        when(firebaseAuth.getUser(FirebaseRevocationPermissionCheck.PROBE_UID)).thenThrow(
                new FirebaseAuthException(ErrorCode.NOT_FOUND, "sin proyecto", null, null,
                        AuthErrorCode.CONFIGURATION_NOT_FOUND));

        assertThatThrownBy(check::check).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void check_warnsAndPasses_whenFirebaseUnavailable(CapturedOutput output) throws Exception {
        when(firebaseAuth.getUser(FirebaseRevocationPermissionCheck.PROBE_UID)).thenThrow(
                new FirebaseAuthException(ErrorCode.UNAVAILABLE, "caído", null, null, null));

        assertThatCode(check::check).doesNotThrowAnyException();

        assertThat(output.getOut()).contains("errorCode=UNAVAILABLE");
    }
}
