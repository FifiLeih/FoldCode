package dev.foldcode.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PythonPackageCommandTest {
    @Test
    fun pipAliasesAndModuleInvocationUseTheSameProjectTarget() {
        val expected = "mkdir -p \"\$FOLDCODE_PROJECT_ROOT/.foldcode/python\" && " +
            "python -m pip install --target \"\$FOLDCODE_PROJECT_ROOT/.foldcode/python\" requests"

        listOf(
            "pip install requests",
            "pip3 install requests",
            "python -m pip install requests",
            "python3 -m pip install requests",
        ).forEach { command -> assertEquals(command, expected, preparePythonPackageCommand(command)) }
    }

    @Test
    fun explicitInstallDestinationsArePreserved() {
        assertEquals(
            "python -m pip install --target /tmp/packages demo",
            preparePythonPackageCommand("pip install --target /tmp/packages demo"),
        )
        assertEquals(
            "python -m pip install --user demo",
            preparePythonPackageCommand("python -m pip install --user demo"),
        )
    }

    @Test
    fun readOnlyPipCommandsRemainReadOnly() {
        assertEquals("python -m pip --version", preparePythonPackageCommand("pip --version"))
        assertEquals("python -m pip list", preparePythonPackageCommand("python3 -m pip list"))
        assertNull(preparePythonPackageCommand("python main.py"))
    }
}
