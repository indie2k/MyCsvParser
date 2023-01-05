package com.indie2k.test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class MyCsvParser {
	
	// ---------
	// CSV 파싱
	// ---------
	// CSV는 공식 명세(RFC4180)도 있으나, 엑셀은 공식 명세를 완벽히 맞추지 않아도 융통성있게 문서를 읽어온다
	// (가령, 공식 명세는 모든 행의 칼럼수가 같아야 된다고 한, 엑셀은 달라도 있는 만큼 읽어낸다)
	// MyCsvParser 로직은 엑셀에서 읽어오는 기준으로 작성한 로직임.
	//
	// * 기본 구분자 : ',' or '\r\n' (줄바꿈)
	//
	// * 기본 쿼테이션 문자 : '"'
	//
	// * 쿼테이션 상태 진입 : '"'이 구분자의 바로 뒤에 나오는 경우 쿼테이션 상태가 켜진다. 
	//                   ex> ab,"cde", "fgh" : 2번째 칼럼의 "는 쿼테이션 상태를 켠다.
	//                                         그러나 3번째는 아님 (',' 과 '"' 사이 공백이 있으므로 아님)
	//                                         엑셀에서 이런 경우 [ab|cde| "fgh"] 로 읽힌다.
	//
	// * 쿼테이션 상태에서는 구분자','나 쿼테이션 문자 '"'도 표현 가능하다. ( 쿼테이션 상태에서 "" -> " )
	//                   ex> ab,"cd""e,fg""h",ij -> [ab|cd"e,fg"h|ij]
	// 
	// * 쿼테이션 상태에서는 줄바꿈도 가능하다.
	//                   ex> ab,"c<CR><LF> --> [ab|c|fg]
	//                       d",fg                |d|  
	//
	// * 쿼테이션 상태 종료 : 쿼테이션 상태에서 '"'이 한번 나오고 뒤에 '"' 이외의 어떤 값이라도 오는 경우 쿼테이션 상태가 꺼진다.
	//                   (쿼테이션 상태에서 '"'이 나온 경우 쿼테이션 상태가 꺼지지 않는 유일한 상황은 '""'가 유일하다)
	//                   ex> ab,"cde"<CR><LF> --> 쿼테이션 종료
	//                       ab,"cde",fg      --> 쿼테이션 종료
	//                       ab,"cde"",fg     --> 쿼테이션 종료 안됨 ( ab|cde",fg )로 해석
	//
	// * 쿼테이션 상태 재 진입 : 쿼테이션 상태가 한번 종료되면 다음 구분자(',' or 줄바꿈)가 올 때까지는 다시 '"'이 나타나도 
	//                     쿼테이션 상태가 다시 켜지지 않는다.
	//
	// * 쿼테이션 상태가 아닌 경우에는 모든 문자를 문자 그대로 해석한다.
	//
	// * <CR><LF> 줄바꿈 : 쿼테이션 상태에서는 내용의 줄바꿈, 쿼테이션 상태 아닌 경우에는 라인 줄바꿈으로써의 기능.
	//
	// * 한계 : 엑셀 기준 1,048,576 라인, 16,384 칼럼, 칼럼당 최대 32,767 글자로 제한하고 넘는 경우 오류로 간주한다.
	// -----------------------------------------------------------------------------------------
	
	private static final char DEFAULT_SEPARATOR = ',';
	private static final char DEFAULT_QUOTE_CHAR = '"';
	private static final String NEW_LINE = "\n";
	private static final int EXCEL_MAX_ROW_CNT = 1_048_576;	// 최대 ROW 수 ( 엑셀 기준 )
	private static final int EXCEL_MAX_COL_CNT = 16_384;	// 최대 COL 수 ( 엑셀 기준 )
	private static final int EXCEL_MAX_CHAR_CNT = 32_767;	// 셀당 최대 글자 수 ( 엑셀 기준 )

	private boolean isMultiLineInQuotes = false;			// 쿼테이션 상태에서 줄바꿈 되는 경우 true
	private String tempField = "";							// 쿼테이션 상태에서 줄바꿈 된 경우, 해당 시점까지의 Col 내용 임시 저장
	private String[] pendingFieldLine = new String[] {};	// 쿼테이션 상태에서 줄바꿈 된 경우, 해당 시점까지의 한 Row 내용 임시 저장

	public static void main(String[] args) {
		
		
		File file = new File("resources/csvtest/test.csv");
		MyCsvParser csvParser = new MyCsvParser();
		
		//----
		//파싱
		//----
		List<String[]> result = csvParser.readFile(file, 0, "UTF-8"); //file을 열어서 0라인부터 파싱 -> Lsit<String[]>
		
		//--------
		//결과확인
		//--------
		int listIndex = 0;
		for(String[] arrays : result) {
			System.out.println("\nString[" + listIndex++ + "] : " + Arrays.toString(arrays));
			
			int index = 0;
			for(String array : arrays) {
				System.out.println(index++ + " : " + array);
			}
		}	
	}
	public List<String[]> readFile(File csvFile, int skipLine, String encoding) {
		// ------------------------------
		// CSV 파일 1개당 이 로직으로 들어와서 처리됨
		// ------------------------------
		
		List<String[]> result = new ArrayList<>();
		int indexLine = 1;
		
		try(BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(csvFile), encoding))) {
			
			String line;
			while((line = br.readLine()) != null) {
				
				if(indexLine++ <= skipLine) continue; // 헤더 SKIP 카운트
				
				// --------------------------------
				// CSV 텍스트 파일을 Line By Line으로 처리
				// --------------------------------
				String[] csvLineInArray = parseLine(line, DEFAULT_SEPARATOR, DEFAULT_QUOTE_CHAR); // csv텍스트 1Row -> String[]으로 파싱
				if(isMultiLineInQuotes) {
					// 쿼테이션 상태에서 개행 문자로 인해 라인이 종료된 경우
					// pendingFieldLine : CSV의 내용중 개행 문자가 있어서 라인이 바뀐 경우 라인이 완전히 종료될 때까지 한 Row의 필드 값들을 누적하는 String[] 변수
					pendingFieldLine = joinArrays(pendingFieldLine, csvLineInArray); // pendingFieldLine += csvLineInArray
				} else {
					// 라인이 완전히 정상 종료된 경우 -> 지금까지 결과 result에 add
					if(pendingFieldLine != null && pendingFieldLine.length > 0) {
						// pendingFieldLine이 있으면 지금까지의 pendingFieldLine + parseLine() 결과가 result에 add됨
						result.add(joinArrays(pendingFieldLine, csvLineInArray)); // pendingFieldLine += csvLineInArray
						pendingFieldLine = new String[] {}; //초기화						
					} else {
						// pendingFieldLine이 없으면 이번 parseLine() 결과만 result에 add됨 ( 일반적인 개행 없는 라인 )
						result.add(csvLineInArray);
					}
					
					// 최대 Row 체크
					if(result.size() > EXCEL_MAX_ROW_CNT) {
						throw new RuntimeException("Row가 엑셀의 최대 카운트("+EXCEL_MAX_ROW_CNT+") 초과되었습니다.");
					}
				}
			}
			
			// ----------------
			// 전체 Row 처리 종료 후
			// ----------------
			if(tempField.length() > 0) { // 정상적인 상황은 아닌데 CSV형식을 제대로 못맞춘 경우, 마무리 해줌(" 열기만 하고 닫지 않은 경우 등)
				result.add(joinArrays(pendingFieldLine, new String[] {tempField})); // pendingFieldLine += tempFeild
			}
			
		} catch (Exception e) {
			throw new RuntimeException("CSV 파일 읽는 중 익셉션 발생", e);
		}
		
		return result;
	}
	
	private String[] parseLine(String line, char separator, char quoteChar) {
		
		// ------------------------------------------
		// 해당 로직은 CSV 텍스트의 Row 별로 들어온다 ( Line )
		// ------------------------------------------
		List<String> result = new ArrayList<String>();
		
		boolean isQuoteState    = false;    // 쿼테이션 상태 여부
		boolean isPreQuote      = false;    // 직전 글자가 " 인지 여부 ( 단, 쿼테이션 시작하는 " 는 제외 )
		boolean isPreSeparator  = true;     // 직전 글자가 구분자 (',' or 개행)인지 여부 - 처음엔 true로 시작
		
		StringBuilder field = new StringBuilder(); // 한 필드의 값
		
		// 매 Row당 처음 1번은 쿼테이션 개행 상태인지 체크
		if(isMultiLineInQuotes) {
			field.append(tempField).append(NEW_LINE);  // field에 <-- 전 Line의 필드값(pendingFeild) + NEW_LINE
			tempField = "";                            // 임시값 초기화
			isQuoteState = true;             // 개행 상태는 기본적으로 쿼테이션 상태임
			isPreSeparator = false;          // 직전 글자 구분자 아님 ( 쿼테이션 상태에서 개행 중 )
			isMultiLineInQuotes = false;     // 멀티라인 여부 다시 false로
		}
		for(char c : line.toCharArray()) {
			
			// ----------
			// 한 글자씩 처리
			// ----------
			if(c == quoteChar) {
				// ----------------
				// 이번 글자가 " 인 경우
				// ----------------
				if(isPreSeparator) {
					// 직전 글자가 구분자인 경우
					
					isQuoteState = true; // 직전 글자가 구분자 이면서 이번 글자가 " 인 경우 -> 쿼테이션 상태 On
					isPreQuote = false;  // 직전 " 여부 false ( 쿼테이션 상태 시작하는 " 는 제외 )
				} else {
					// 직전 글자가 구분자가 아닌 경우
					
					if(isQuoteState) {
						// 쿼테이션 상태인 경우
						
						if(isPreQuote) {
							// 직전 글자가 " 인 경우 ( 쿼테이션 시작 " 는 제외 ) -> 이번 문자 " 를 찍어줌
							field.append(c); // " 추가
							isPreQuote = false; // 직전 " 여부 false ( 쿼테이션 상태 시작하는 " 는 제외 )
						} else {
							// 직전 글자가 " 아닌 경우
							// -> ""의 첫 " 상태 표시만 함
							isPreQuote = true; // 쿼테이션 상태에서 직전글자가 " 아닌 상태로 " 이 나온 경우만 true
						}
					} else {
						// 쿼테이션 상태 아닌 경우 -> 모두 일반 문자 취급
						field.append(c);
						isPreQuote = false;  // 직전 " 여부 false ( 쿼테이션 상태 시작하는 " 는 제외 )
					}
				}
			
				//앞글자 구분자 여부 Off
				isPreSeparator = false;
				
			} else if(c == separator) {
				// ---------
				// ',' 인 경우
				// ---------
				if(isPreSeparator) {
					// 직전 글자가 구분자인 경우
				
					// 쿼테이션 상태가 아닌 경우의 ','는 무조건 구분자의 의미임
					result.add(field.toString()); // 지금까지 값 result에 추가
					field.setLength(0); // 필드 초기화
				
				} else {
					// 직전 글자가 구분자가 아닌 경우
				
					if(isQuoteState) {
						// 쿼테이션 상태인 경우
								
						if(isPreQuote) {
							// 직전 글자가 " 인 경우 ( 쿼테이션 시작 " 는 제외 )
							// -> 쿼테이션 모드 종료
							isQuoteState = false;
							// -> 구분자 역할
							result.add(field.toString()); // 지금까지 값 result에 추가
							field.setLength(0); // 필드 초기화
							isPreSeparator = true; // 직전구분자여부 On
							
						} else {
							// 직전 글자가 " 아닌 경우
							// -> 일반 문자로 취급 ( 계속 쿼테이션 상태 유지 )
							field.append(c);
						}
				
					} else {
						// 쿼테이션 상태 아닌 경우
				
						//쿼테이션 상태가 아닌 경우의 ','는 무조건 구분자의 의미임
						result.add(field.toString()); // 지금까지 값 result에 추가
						field.setLength(0); // 필드 초기화
						isPreSeparator = true; // 직전 구분자 여부 On
					}
				}
		
				// 앞글자 " 여부 Off
				isPreQuote = false;
				
				// 최대 칼럼수 넘으면 에러
				_checkMaxColCnt(result.size() + pendingFieldLine.length);
			
			} else {
				// --------------------------
				// 일반 문자 ('"' / ',' 이외 문자)
				// --------------------------
				if(isPreQuote) {  // 직전 글자가 " 이고 이번엔 일반 문자
					// 쿼테이션 모드 종료
					isQuoteState = false;
				}
			
				// field에 add
				field.append(c);
				// 앞글자 " 여부 Off
				isPreQuote = false;
				// 앞글자 구분자 여부 Off
				isPreSeparator = false;
			}
		
			// 셀당 허용 문자수 체크
			if(field.length() > EXCEL_MAX_CHAR_CNT) {
				throw new RuntimeException("1개의 셀에 허용된 문자 수("+EXCEL_MAX_CHAR_CNT+") 초과");
			}
		}
		
		// ------ 
		// 라인 종료
		// ------
		if(isQuoteState) {
			// 라인이 종료되었는데 쿼테이션 상태가 true인 경우 -> 쿼테이션 내부 내용헤서 개행되었음
			tempField = field.toString(); // pendingField에 지금까지의 field 내용 임시로 넣어놓음
			isMultiLineInQuotes = true; // 개행 여부 On
		} else {
			// 정상적으로 라인 종료된 경우
			// 마지막 field 값 -> result에 넣음
			result.add(field.toString());
			// 최대 칼럼수 넘으면 에러
			_checkMaxColCnt(result.size() + pendingFieldLine.length);
		}
		
		// 지금까지의 result 리턴
		return result.toArray(new String[0]);
		
	}
	
	
	private void _checkMaxColCnt(int cnt) {
		// 최대 칼럼수 체크
		if(cnt > EXCEL_MAX_COL_CNT) {
			throw new RuntimeException("최대 칼럼 수 ("+EXCEL_MAX_COL_CNT+") 초과");
		}
	}
	
	/**
	 * String 배열 합치기 
	 * @param array1
	 * @param array2
	 * @return
	 */
	private String[] joinArrays(String[] array1, String[] array2) {
		return Stream.concat(Arrays.stream(array1), Arrays.stream(array2)).toArray(String[]::new);
	}

}

