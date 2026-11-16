package ngj.ast;

public sealed interface TopLevelDecl extends Node 
    permits FnDecl, DataDecl, EnumDecl {}
