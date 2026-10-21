// Safe public business failure; OfficeErrors maps status/code without exposing dependency details.
package edu.municipal.documents;
public class OfficeProblem extends RuntimeException {
    public final int status;
    public final String code;
    public OfficeProblem(int status,String code,String message) { super(message);this.status=status;this.code=code; }
}
