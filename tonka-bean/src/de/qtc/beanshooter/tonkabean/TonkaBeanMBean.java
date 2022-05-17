package de.qtc.beanshooter.tonkabean;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.util.Map;

public interface TonkaBeanMBean
{
    public String ping();
    public String[] shellInit();
    public String toServerDir(String current, String change) throws IOException, InvalidPathException;

    public byte[] executeCommand(String[] cmd, String cwd, Map<String,String> env) throws IOException, InterruptedException;
    public void executeCommandBackground(String[] cmd, String cwd, Map<String,String> env) throws IOException;

    public byte[] downloadFile(String filename) throws IOException;
    public String uploadFile(String destination, byte[] content) throws IOException;
}