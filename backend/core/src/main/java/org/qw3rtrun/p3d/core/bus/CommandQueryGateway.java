package org.qw3rtrun.p3d.core.bus;

public interface CommandQueryGateway {

    <R> R send(Object req);
    <R> R send(Object req, Class<R> returnType);

}
