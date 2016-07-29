/**
 * 
 */
package com.thinkbiganalytics.metadata.modeshape.auth;

/**
 *
 * @author Sean Felten
 */
public class ModeShapeReadWritePrincipal extends ModeShapePrincipal {

    /**
     * 
     */
    private static final long serialVersionUID = 1L;
    
    public ModeShapeReadWritePrincipal() {
        super("readwrite");
    }

}
